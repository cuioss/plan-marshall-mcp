/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


import com.fasterxml.jackson.databind.JsonNode;

/**
 * The daemon-side MCP traffic record ({@code traffic.jsonl}, written by the spike recorder of {@code pm-mcpd} when
 * {@code pm.spike.traffic-file} is set): every message received ({@code rx}) and sent ({@code tx}) with its MCP
 * connection. A response is matched to its request by connection and JSON-RPC id; sessionless hosts get one
 * transient connection per request.
 *
 * @param messages the recorded lines in order
 */
record McpTraffic(List<Message> messages) {

    /**
     * One recorded message.
     *
     * @param tMs        arrival or send time
     * @param direction  {@code rx} or {@code tx}
     * @param connection the MCP connection id
     * @param body       the JSON-RPC message
     */
    record Message(long tMs, String direction, String connection, JsonNode body) {

        String method() {
            return body.path("method").asText(null);
        }

        String idKey() {
            return connection + "#" + body.path("id").toString();
        }

        boolean request() {
            return method() != null && body.has("id");
        }

        boolean response() {
            return method() == null && body.has("id");
        }
    }

    /**
     * One tool call seen by the daemon.
     *
     * @param tool     the tool name
     * @param request  the {@code tools/call} request
     * @param response its response, {@code null} if none was sent
     */
    record Call(String tool, Message request, Message response) {

        /** @return whether the daemon answered with a result that is no tool error */
        boolean succeeded() {
            return response != null && response.body().has("result")
                    && !response.body().path("result").path("isError").asBoolean(false);
        }

        /** @return the error text of the response, {@code null} on success */
        String errorText() {
            if (response == null) {
                return "no response";
            }
            if (response.body().has("error")) {
                return response.body().get("error").toString();
            }
            if (!succeeded()) {
                return response.body().path("result").path("content").toString();
            }
            return null;
        }
    }

    /**
     * @param file the record
     * @return the parsed record, empty if the file does not exist
     * @throws IOException if it cannot be read
     */
    static McpTraffic read(Path file) throws IOException {
        var messages = new ArrayList<Message>();
        if (Files.exists(file)) {
            for (var line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                var node = StubEvent.JSON.readTree(line);
                messages.add(new Message(node.path("t_ms").asLong(), node.path("dir").asText(),
                        node.path("connection").asText(null), node.path("message")));
            }
        }
        return new McpTraffic(List.copyOf(messages));
    }

    /** @return every method the daemon received, with its count, in order of first arrival */
    Map<String, Integer> methodsReceived() {
        var methods = new LinkedHashMap<String, Integer>();
        messages.stream().filter(message -> "rx".equals(message.direction()) && message.method() != null)
                .forEach(message -> methods.merge(message.method(), 1, Integer::sum));
        return methods;
    }

    /**
     * @param request a received request
     * @return the response the daemon sent to it, or {@code null}
     */
    Message responseTo(Message request) {
        return messages.stream().filter(message -> "tx".equals(message.direction()) && message.response()
                && message.idKey().equals(request.idKey())).findFirst().orElse(null);
    }

    /**
     * @param request a received request
     * @return every message the daemon sent on the request's connection after it (notifications and the response)
     */
    List<Message> sentAfter(Message request) {
        return messages.stream().filter(message -> "tx".equals(message.direction())
                && request.connection() != null && request.connection().equals(message.connection())
                && message.tMs() >= request.tMs()).toList();
    }

    /**
     * @param method a method
     * @return the received requests of that method
     */
    List<Message> requests(String method) {
        return messages.stream().filter(message -> "rx".equals(message.direction()) && message.request()
                && method.equals(message.method())).toList();
    }

    /** @return the tool names the daemon sent in any {@code tools/list} result */
    Set<String> toolsListed() {
        var names = new LinkedHashSet<String>();
        for (var request : requests("tools/list")) {
            var response = responseTo(request);
            if (response != null) {
                response.body().path("result").path("tools")
                        .forEach(tool -> names.add(tool.path("name").asText()));
            }
        }
        return names;
    }

    /** @return every {@code tools/call} the daemon received, with its response */
    List<Call> calls() {
        return requests("tools/call").stream().map(request -> new Call(
                request.body().path("params").path("name").asText(), request, responseTo(request))).toList();
    }
}
