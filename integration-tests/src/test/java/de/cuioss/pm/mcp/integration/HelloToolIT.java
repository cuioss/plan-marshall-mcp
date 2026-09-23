/*
 * Copyright © 2026-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.pm.mcp.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Calls the {@code hello} tool through the MCP Streamable HTTP transport as a client would:
 * {@code initialize}, {@code notifications/initialized}, {@code tools/call}.
 */
@DisplayName("MCP hello tool in the container")
class HelloToolIT extends BaseIntegrationTest {

    private static final String MCP_PATH = "/mcp";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_VERSION = "2025-11-25";

    @Test
    @DisplayName("tools/call hello returns the greeting")
    void shouldGreetViaMcp() {
        var initialize = mcpRequest(null).body("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"%s","capabilities":{},
                  "clientInfo":{"name":"plan-marshall-mcp-it","version":"1.0"}}}
                """.formatted(PROTOCOL_VERSION))
                .post(MCP_PATH);
        assertEquals(200, initialize.statusCode());
        assertEquals("plan-marshall-mcp", jsonRpcResult(initialize).getString("result.serverInfo.name"));
        var sessionId = initialize.header(SESSION_HEADER);

        mcpRequest(sessionId).body("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """)
                .post(MCP_PATH).then().statusCode(202);

        var call = mcpRequest(sessionId).body("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"hello","arguments":{"name":"Container"}}}
                """)
                .post(MCP_PATH);
        assertEquals(200, call.statusCode());
        var result = jsonRpcResult(call);
        assertFalse(result.getBoolean("result.isError"));
        assertEquals("Hello, Container!", result.getString("result.content[0].text"));
    }

    private static RequestSpecification mcpRequest(String sessionId) {
        var spec = given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION);
        return sessionId == null ? spec : spec.header(SESSION_HEADER, sessionId);
    }

    /**
     * The server may answer a request either with a JSON body or with an SSE stream carrying the
     * JSON-RPC response as {@code data:} event.
     */
    private static JsonPath jsonRpcResult(Response response) {
        var body = response.asString();
        if (response.contentType().startsWith("text/event-stream")) {
            body = body.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).trim())
                    .filter(data -> data.contains("\"result\"") || data.contains("\"error\""))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No JSON-RPC response in SSE stream: " + response.asString()));
        }
        return JsonPath.from(body);
    }
}
