/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;


import de.cuioss.pm.api.MachinePaths;
import de.cuioss.pm.mcp.server.runtime.PmMcpd;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Access to the runtime a {@code @QuarkusTest} runs against (base, socket, token) and the request shapes of the
 * relay. Carries the global {@link RuntimeBaseResource}.
 */
@WithTestResource(value = RuntimeBaseResource.class, scope = TestResourceScope.GLOBAL)
public final class TestRuntime {

    /** The stateless protocol version. */
    public static final String STATELESS = "2026-07-28";

    private TestRuntime() {
    }

    /** @return the machine paths of the running test application */
    public static MachinePaths paths() {
        return new MachinePaths(Path.of(ConfigProvider.getConfig().getValue(PmMcpd.BASE_PROPERTY, String.class)),
                MachinePaths.Os.current());
    }

    /**
     * @param paths machine paths
     * @return the runtime token written there
     */
    public static String token(MachinePaths paths) {
        try {
            return Files.readString(paths.runtimeToken()).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the runtime token of the running test application */
    public static String token() {
        return token(paths());
    }

    /**
     * @param token the runtime token
     * @return the Authorization header
     */
    public static Map<String, String> bearer(String token) {
        return new HashMap<>(Map.of("Authorization", "Bearer " + token));
    }

    /**
     * Builds a stateless ({@value #STATELESS}) MCP request in the relay's form.
     *
     * @param id     the JSON-RPC id
     * @param method the method
     * @param params the params without {@code _meta}
     * @return the JSON-RPC message
     */
    public static JsonObject statelessMessage(int id, String method, JsonObject params) {
        var meta = new JsonObject().put("io.modelcontextprotocol/protocolVersion", STATELESS)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject().put("elicitation", new JsonObject()))
                .put("io.modelcontextprotocol/clientInfo", new JsonObject().put("name", "test").put("version", "1"));
        var merged = params.copy();
        merged.put("_meta", merged.getJsonObject("_meta", new JsonObject()).mergeIn(meta));
        return new JsonObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", merged);
    }

    /**
     * The headers of a stateless MCP request in the relay's form.
     *
     * @param credentials the credential headers
     * @param message     the message
     * @return the headers
     */
    public static Map<String, String> mcpHeaders(Map<String, String> credentials, JsonObject message) {
        var headers = new HashMap<>(credentials);
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("Content-Type", "application/json");
        headers.put("Mcp-Method", message.getString("method"));
        var params = message.getJsonObject("params");
        if (params != null && params.containsKey("name")) {
            headers.put("Mcp-Name", params.getString("name"));
        }
        if (params != null && params.getJsonObject("_meta", new JsonObject()).containsKey(
                "io.modelcontextprotocol/protocolVersion")) {
            headers.put("MCP-Protocol-Version", STATELESS);
        }
        return headers;
    }

    /**
     * Sends a stateless MCP request in the relay's form over the socket.
     *
     * @param paths       the machine paths
     * @param credentials the credential headers
     * @param message     the message
     * @return the response
     * @throws IOException if the exchange fails
     */
    public static UdsHttp.Response mcp(MachinePaths paths, Map<String, String> credentials, JsonObject message)
            throws IOException {
        return UdsHttp.request(paths.socket(), "POST", "/mcp", mcpHeaders(credentials, message), message.encode());
    }

    /**
     * Extracts the JSON-RPC response of a plain JSON or an SSE body: the last {@code data:} line carrying an id.
     *
     * @param body the response body
     * @return the response message
     */
    public static JsonObject result(String body) {
        if (body.stripLeading().startsWith("{")) {
            return new JsonObject(body);
        }
        JsonObject last = null;
        for (var line : body.split("\n")) {
            if (line.startsWith("data:")) {
                var message = new JsonObject(line.substring(5).strip());
                if (message.containsKey("id")) {
                    last = message;
                }
            }
        }
        return last;
    }
}
