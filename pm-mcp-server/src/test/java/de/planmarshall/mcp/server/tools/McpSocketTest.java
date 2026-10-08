/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


import de.planmarshall.mcp.server.test.TestRuntime;
import de.planmarshall.mcp.server.test.TestSecrets;
import de.planmarshall.mcp.server.test.ToolSchemaRules;
import de.planmarshall.mcp.server.test.UdsHttp;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@QuarkusTest
@DisplayName("MCP over the Unix socket, sessionless")
class McpSocketTest {

    private static Map<String, String> runtime() {
        return TestRuntime.bearer(TestRuntime.token());
    }

    private static JsonObject call(Map<String, String> credentials, String tool, JsonObject arguments)
            throws IOException {
        var message = TestRuntime.statelessMessage(7, "tools/call",
                new JsonObject().put("name", tool).put("arguments", arguments));
        var response = TestRuntime.mcp(TestRuntime.paths(), credentials, message);
        assertEquals(200, response.status(), response.body());
        assertNull(response.headers().get("mcp-session-id"));
        return TestRuntime.result(response.body());
    }

    private static String text(JsonObject result) {
        return result.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text");
    }

    @Nested
    @DisplayName("protocol forms")
    class Forms {

        @Test
        @DisplayName("answers server/discover without a session")
        void shouldDiscover() throws Exception {
            var response = TestRuntime.mcp(TestRuntime.paths(), runtime(),
                    TestRuntime.statelessMessage(1, "server/discover", new JsonObject()));

            assertEquals(200, response.status(), response.body());
            assertNull(response.headers().get("mcp-session-id"));
            var result = TestRuntime.result(response.body()).getJsonObject("result");
            assertEquals("plan-marshall-mcp", result.getJsonObject("_meta")
                    .getJsonObject("io.modelcontextprotocol/serverInfo").getString("name"));
            assertTrue(result.getJsonArray("supportedVersions").contains(TestRuntime.STATELESS));
        }

        @Test
        @DisplayName("answers initialize of a session-opening host without Mcp-Session-Id, then serves it sessionless")
        void shouldInitializeSessionless() throws Exception {
            var initialize = new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                    .put("params", new JsonObject().put("protocolVersion", "2025-11-25")
                            .put("capabilities", new JsonObject())
                            .put("clientInfo", new JsonObject().put("name", "opencode").put("version", "1")));
            var headers = TestRuntime.mcpHeaders(runtime(), initialize);

            var init = UdsHttp.request(TestRuntime.paths().socket(), "POST", "/mcp", headers, initialize.encode());

            assertEquals(200, init.status(), init.body());
            assertNull(init.headers().get("mcp-session-id"));
            assertEquals("2025-11-25",
                    new JsonObject(init.body()).getJsonObject("result").getString("protocolVersion"));

            var call = new JsonObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                    .put("params", new JsonObject().put("name", "hello").put("arguments",
                            new JsonObject().put("name", "socket")));
            var callHeaders = TestRuntime.mcpHeaders(runtime(), call);
            callHeaders.put("MCP-Protocol-Version", "2025-11-25");
            var response = UdsHttp.request(TestRuntime.paths().socket(), "POST", "/mcp", callHeaders,
                    call.encode());

            assertEquals(200, response.status(), response.body());
            assertEquals("Hello, socket!", text(TestRuntime.result(response.body())));
        }
    }

    @Nested
    @DisplayName("tool surface")
    class Surface {

        @Test
        @DisplayName("every tools/list schema obeys the flat-schema constraints")
        void shouldListClosedSchemas() throws Exception {
            var response = TestRuntime.mcp(TestRuntime.paths(), runtime(),
                    TestRuntime.statelessMessage(2, "tools/list", new JsonObject()));
            var tools = TestRuntime.result(response.body()).getJsonObject("result").getJsonArray("tools");

            var names = new ArrayList<String>();
            var violations = new ArrayList<String>();
            for (var entry : tools) {
                var tool = (JsonObject) entry;
                names.add(tool.getString("name"));
                violations.addAll(ToolSchemaRules.violations(tool.getString("name"), tool.getJsonObject("inputSchema")));
                assertFalse(tool.containsKey("outputSchema"), tool.getString("name"));
            }

            assertTrue(names.containsAll(CoreTools.NAMES), names.toString());
            assertTrue(violations.isEmpty(), violations.toString());
        }

        @Test
        @DisplayName("a core tool stub answers its fixed text")
        void shouldAnswerStub() throws Exception {
            var result = call(runtime(), "pm_state", new JsonObject().put("plan_id", "NO_PLAN"));

            assertFalse(result.getJsonObject("result").getBoolean("isError"));
            assertEquals(CoreTools.stubAnswer("pm_state"), text(result));
        }

        @Test
        @DisplayName("an argument outside the schema is a tool error naming parameter and rule")
        void shouldRefuseInvalidArguments() throws Exception {
            var result = call(runtime(), "pm_wait", new JsonObject().put("worker", "w-1"));

            assertTrue(result.getJsonObject("result").getBoolean("isError"));
            assertEquals("error_code: invalid_arguments\nparameter: worker\nrule: additional_property",
                    text(result));
        }
    }

    @Nested
    @DisplayName("connection metadata reaches the handler through the security identity (gate 10)")
    class Identity {

        @Test
        @DisplayName("a session relay's PM-MCP-* headers")
        void shouldCarrySessionMetadata() throws Exception {
            var credentials = runtime();
            credentials.put("PM-MCP-Client", "claude");
            credentials.put("PM-MCP-Workspace", "/work/repo");
            credentials.put("PM-MCP-Generation", "sg-0001");
            credentials.put("PM-MCP-Relay-Version", "0.1.1");
            credentials.put("PM-MCP-Client-Capabilities", "{\"elicitation\":{}}");

            var identity = new JsonObject(text(call(credentials, "probe_identity", new JsonObject())));

            assertEquals("runtime", identity.getString("principal"));
            var attributes = identity.getJsonObject("attributes");
            assertEquals("claude", attributes.getString("pm.client"));
            assertEquals("/work/repo", attributes.getString("pm.workspace"));
            assertEquals("sg-0001", attributes.getString("pm.generation"));
            assertEquals("{\"elicitation\":{}}", attributes.getString("pm.client_capabilities"));
            assertEquals("probe_identity", attributes.getString("pm.mcp_name"));
            assertEquals("unix", attributes.getString("pm.listener"));
            assertEquals("runtime", attributes.getString("pm.credential"));
            assertNotNull(identity.getJsonObject("meta").getString("io.modelcontextprotocol/protocolVersion"));
        }

        @Test
        @DisplayName("a worker's job token, with the session headers ignored")
        void shouldBindJobToken() throws Exception {
            var credentials = new HashMap<>(Map.of("PM-MCP-Job-Token", TestSecrets.JOB_TOKEN,
                    "PM-MCP-Generation", TestSecrets.JOB_ID, "PM-MCP-Client", "claude"));

            var identity = new JsonObject(text(call(credentials, "probe_identity", new JsonObject())));

            assertEquals(TestSecrets.JOB_ID, identity.getString("principal"));
            var attributes = identity.getJsonObject("attributes");
            assertEquals("job", attributes.getString("pm.credential"));
            assertEquals(TestSecrets.JOB_ID, attributes.getString("pm.job_id"));
            assertFalse(attributes.containsKey("pm.client"));
        }

        @Test
        @DisplayName("an unknown job token is refused with 401")
        void shouldRefuseUnknownJobToken() throws Exception {
            var message = TestRuntime.statelessMessage(3, "tools/list", new JsonObject());

            var response = TestRuntime.mcp(TestRuntime.paths(), Map.of("PM-MCP-Job-Token", "unknown"), message);

            assertEquals(401, response.status());
        }
    }

    @Nested
    @DisplayName("messages on the call's own response stream (gate 10)")
    class Stream {

        @Test
        @DisplayName("progress notifications precede the result on the SSE response")
        void shouldStreamProgress() throws Exception {
            var message = TestRuntime.statelessMessage(8, "tools/call", new JsonObject().put("name",
                    "probe_progress").put("arguments", new JsonObject())
                    .put("_meta", new JsonObject().put("progressToken", "p-1")));

            var response = TestRuntime.mcp(TestRuntime.paths(), runtime(), message);

            assertTrue(response.headers().get("content-type").startsWith("text/event-stream"));
            var progress = response.body().lines().filter(line -> line.contains("notifications/progress")).count();
            assertEquals(3, progress);
            assertEquals("progress sent", text(TestRuntime.result(response.body())));
        }

        @Test
        @DisplayName("elicitation under the stateless protocol is an input_required result answered by a retry")
        void shouldElicitByRoundTrip() throws Exception {
            var first = call(runtime(), "probe_elicit", new JsonObject());

            var result = first.getJsonObject("result");
            assertEquals("input_required", result.getString("resultType"));
            assertEquals("elicitation/create", result.getJsonObject("inputRequests").getJsonObject("choice")
                    .getString("method"));

            var retry = TestRuntime.statelessMessage(9, "tools/call", new JsonObject().put("name", "probe_elicit")
                    .put("arguments", new JsonObject())
                    .put("inputResponses", new JsonObject().put("choice", new JsonObject().put("action", "accept")
                            .put("content", new JsonObject().put("choice", "issue")))));
            var answered = TestRuntime.result(TestRuntime.mcp(TestRuntime.paths(), runtime(), retry).body());

            assertEquals("answer: issue", text(answered));
        }
    }
}
