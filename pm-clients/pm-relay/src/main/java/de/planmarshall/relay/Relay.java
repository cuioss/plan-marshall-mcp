/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.relay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import de.planmarshall.api.PmVersion;
import de.planmarshall.api.http.HttpExchange;
import de.planmarshall.api.http.HttpRequest;
import de.planmarshall.api.json.JsonNumber;
import de.planmarshall.api.json.JsonTree;
import de.planmarshall.api.runtime.RuntimeClient;
import de.planmarshall.api.runtime.UnauthorizedException;

/**
 * The STDIO relay of {@code pm-mcp serve}: reads newline-delimited JSON-RPC from {@code stdin},
 * forwards each message as {@code POST /mcp} with the connection metadata, and writes every message
 * the runtime returns for it to {@code stdout} as one flushed line, JSON-RPC ids unchanged and
 * Server-Sent Events unwrapped. Diagnostics go to {@code stderr} only. Each message is forwarded on
 * its own virtual thread, so a held call never blocks the next message or a cancellation. The relay
 * ends when {@code stdin} closes, or, as a worker relay, when the runtime refuses its job token.
 */
final class Relay {

    /** JSON-RPC error code of the closed code {@code runtime_unavailable}. */
    static final int RUNTIME_UNAVAILABLE_CODE = -32000;
    static final String RUNTIME_UNAVAILABLE = "runtime_unavailable";
    static final int INVALID_REQUEST = -32600;
    static final int INTERNAL_ERROR = -32603;
    static final String MCP_PATH = "/mcp";

    private static final int ANSWERED_MEMORY = 4096;

    private final RuntimeClient runtime;
    private final RelayMode mode;
    private final InputStream in;
    private final PrintWriter out;
    private final PrintWriter err;
    private final ProtocolData protocol = new ProtocolData();
    private final Set<String> answered = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > ANSWERED_MEMORY;
                }
            }));
    private final CompletableFuture<Integer> termination = new CompletableFuture<>();

    /**
     * @param runtime the runtime client (session: runtime token and on-demand start; worker: job
     *                token, never a start)
     * @param mode    the identity the relay carries
     * @param in      the host's {@code stdin}
     * @param out     the host's {@code stdout}, JSON-RPC only
     * @param err     the diagnostics stream
     */
    Relay(RuntimeClient runtime, RelayMode mode, InputStream in, PrintWriter out, PrintWriter err) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    /**
     * Relays until {@code stdin} closes or a fatal refusal ends the relay.
     *
     * @return the exit code
     */
    int run() {
        Thread.ofPlatform().daemon().name("pm-mcp-stdin").start(this::readLoop);
        return termination.join();
    }

    private void readLoop() {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    accept(line);
                }
            }
        } catch (IOException e) {
            diagnostic("reading stdin failed: " + e.getMessage());
        }
        termination.complete(0);
    }

    /**
     * Handles one line from {@code stdin}.
     *
     * @param line one JSON-RPC message
     */
    void accept(String line) {
        Map<String, Object> message;
        try {
            message = JsonTree.asObject(JsonTree.parse(line));
        } catch (IOException e) {
            message = null;
        }
        if (message == null) {
            diagnostic("dropping a line that is no JSON-RPC object");
            emit(error(null, INVALID_REQUEST, "invalid_request"));
            return;
        }
        var method = JsonTree.string(message, "method");
        var params = JsonTree.object(message, "params");
        var isRequest = method != null && message.containsKey("id");
        if ("initialize".equals(method)) {
            protocol.recordInitialize(params);
        }
        var snapshot = protocol.forRequest(params);
        if (isRequest && "tools/call".equals(method)) {
            IdentityArguments.stripCall(params);
        }
        var request = new HttpRequest("POST", MCP_PATH, headers(method, params, snapshot),
                JsonTree.writeBytes(message));
        if (isRequest) {
            var id = message.get("id");
            Thread.ofVirtual().start(() -> forwardRequest(id, method, request));
            return;
        }
        // rule 4: the cancellation of an answered request is dropped
        var answeredCancel = "notifications/cancelled".equals(method) && params != null
                && answered.contains(key(params.get("requestId")));
        if (!answeredCancel) {
            Thread.ofVirtual().start(() -> forwardSilently(request));
        }
    }

    private Map<String, String> headers(String method, Map<String, Object> params, ProtocolData.Snapshot snapshot) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", HttpRequest.APPLICATION_JSON);
        headers.put("Accept", "application/json, text/event-stream");
        putSafe(headers, "Mcp-Method", method);
        if ("tools/call".equals(method)) {
            putSafe(headers, "Mcp-Name", JsonTree.string(params, "name"));
        }
        headers.put("PM-MCP-Relay-Version", PmVersion.current());
        putSafe(headers, "PM-MCP-Protocol-Version", snapshot.protocolVersion());
        putSafe(headers, "PM-MCP-Client-Info", snapshot.clientInfo());
        putSafe(headers, "PM-MCP-Client-Capabilities", snapshot.capabilities());
        if (!mode.job()) {
            headers.put("PM-MCP-Client", mode.client());
            headers.put("PM-MCP-Workspace", mode.workspace());
        }
        putSafe(headers, "PM-MCP-Generation", mode.generation());
        return headers;
    }

    private static void putSafe(Map<String, String> headers, String name, String value) {
        if (value != null && value.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
            headers.put(name, value);
        }
    }

    private void forwardRequest(Object id, String method, HttpRequest request) {
        var idKey = key(id);
        var responded = new AtomicBoolean();
        try (var exchange = runtime.open(request)) {
            relayResponse(exchange, idKey, method, responded);
            if (!responded.get()) {
                answered.add(idKey);
                emit(error(id, INTERNAL_ERROR, "runtime_error: HTTP " + exchange.status()));
                responded.set(true);
            }
        } catch (UnauthorizedException e) {
            refused(e);
        } catch (IOException e) {
            diagnostic(e.getMessage());
        } finally {
            // rule 3: a lost runtime answers the pending request at once
            answered.add(idKey);
            if (!responded.get() && !termination.isDone()) {
                emit(error(id, RUNTIME_UNAVAILABLE_CODE, RUNTIME_UNAVAILABLE));
            }
        }
    }

    private void relayResponse(HttpExchange exchange, String idKey, String method, AtomicBoolean responded)
            throws IOException {
        if (exchange.isEventStream()) {
            var events = exchange.events();
            for (var event = events.next(); event != null; event = events.next()) {
                if (relayMessage(event.data(), idKey, method)) {
                    responded.set(true);
                }
            }
        } else {
            var body = exchange.readBody();
            if (body.length > 0 && relayMessage(new String(body, StandardCharsets.UTF_8), idKey, method)) {
                responded.set(true);
            }
        }
    }

    /** Writes one message (or batch) from the runtime; returns whether it answered the request. */
    private boolean relayMessage(String json, String idKey, String method) {
        Object tree;
        try {
            tree = JsonTree.parse(json);
        } catch (IOException e) {
            diagnostic("dropping a runtime message that is no JSON");
            return false;
        }
        var batch = JsonTree.asArray(tree);
        if (batch != null) {
            var responded = false;
            for (var element : batch) {
                responded |= relayOne(element, idKey, method);
            }
            return responded;
        }
        return relayOne(tree, idKey, method);
    }

    private boolean relayOne(Object tree, String idKey, String method) {
        var message = JsonTree.asObject(tree);
        if (message == null) {
            diagnostic("dropping a runtime message that is no JSON-RPC object");
            return false;
        }
        var isResponse = !message.containsKey("method") && message.containsKey("id")
                && idKey.equals(key(message.get("id")));
        if (isResponse && "tools/list".equals(method)) {
            IdentityArguments.stripToolsList(JsonTree.object(message, "result"));
        }
        if (isResponse) {
            answered.add(idKey);
        }
        emit(message);
        return isResponse;
    }

    private void forwardSilently(HttpRequest request) {
        // rule 2: whatever the runtime returns for a notification or a host response is discarded
        try (var exchange = runtime.open(request)) {
            exchange.readBody();
        } catch (UnauthorizedException e) {
            refused(e);
        } catch (IOException e) {
            diagnostic(e.getMessage());
        }
    }

    private void refused(UnauthorizedException e) {
        if (mode.job()) {
            diagnostic("the runtime refused the job token (401); the worker relay ends");
            termination.complete(1);
        } else {
            diagnostic(e.getMessage());
        }
    }

    static Map<String, Object> error(Object id, int code, String message) {
        var error = new LinkedHashMap<String, Object>();
        error.put("code", JsonNumber.of(code));
        error.put("message", message);
        var response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    private static String key(Object id) {
        return JsonTree.write(id);
    }

    private void emit(Map<String, Object> message) {
        var line = JsonTree.write(message);
        synchronized (out) {
            out.write(line);
            out.write('\n');
            out.flush();
        }
    }

    private void diagnostic(String text) {
        synchronized (err) {
            err.println("pm-mcp serve: " + text);
            err.flush();
        }
    }
}
