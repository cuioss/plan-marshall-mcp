/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


import de.cuioss.pm.mcp.server.test.TestRuntime;
import de.cuioss.pm.mcp.server.test.UdsHttp;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
@DisplayName("No standing notification stream on the sessionless runtime")
class NotificationStreamFilterTest {

    private static Map<String, String> runtime() {
        return TestRuntime.bearer(TestRuntime.token());
    }

    private static final String LISTEN = "subscriptions/listen";
    private static final JsonObject INITIALIZE_PARAMS = new JsonObject().put("protocolVersion", "2025-11-25")
            .put("capabilities", new JsonObject())
            .put("clientInfo", new JsonObject().put("name", "claude-code").put("version", "1"));

    /** A request of a session-opening host: no protocol data in {@code _meta}. */
    private static JsonObject legacy(int id, String method, JsonObject params) {
        return new JsonObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params);
    }

    private static UdsHttp.Response withoutMethodHeader(JsonObject message) throws IOException {
        var headers = new HashMap<>(TestRuntime.mcpHeaders(runtime(), message));
        headers.remove(NotificationStreamFilter.METHOD_HEADER);
        return UdsHttp.request(TestRuntime.paths().socket(), "POST", "/mcp", headers, message.encode());
    }

    private static JsonObject capabilities(UdsHttp.Response response) {
        assertEquals(200, response.status(), response.body());
        return TestRuntime.result(response.body()).getJsonObject("result").getJsonObject("capabilities");
    }

    @Nested
    @DisplayName("over the Unix socket")
    class Socket {

        @Test
        @DisplayName("answers subscriptions/listen with JSON-RPC error -32601")
        void shouldRefuseListen() throws Exception {
            var message = TestRuntime.statelessMessage(5, "subscriptions/listen",
                    new JsonObject().put("filter", new JsonObject().put("toolsListChanged", true)));

            var response = TestRuntime.mcp(TestRuntime.paths(), runtime(), message);

            assertEquals(200, response.status(), response.body());
            var answer = new JsonObject(response.body());
            assertEquals(5, answer.getInteger("id"));
            assertEquals(-32601, answer.getJsonObject("error").getInteger("code"));
            assertFalse(answer.containsKey("result"));
        }

        @Test
        @DisplayName("refuses an unauthenticated subscriptions/listen with 401 before answering it")
        void shouldAuthenticateListen() throws Exception {
            var message = TestRuntime.statelessMessage(6, "subscriptions/listen", new JsonObject());

            var response = TestRuntime.mcp(TestRuntime.paths(), Map.of(), message);

            assertEquals(401, response.status());
        }

        @Test
        @DisplayName("announces tools without listChanged in server/discover")
        void shouldDiscoverWithoutListChanged() throws Exception {
            var response = TestRuntime.mcp(TestRuntime.paths(), runtime(),
                    TestRuntime.statelessMessage(1, "server/discover", new JsonObject()));

            var capabilities = capabilities(response);
            assertEquals(Boolean.FALSE, capabilities.getJsonObject("tools").getValue("listChanged"));
            assertNull(response.headers().get("mcp-session-id"));
        }

        @Test
        @DisplayName("announces tools without listChanged in initialize and stays sessionless")
        void shouldInitializeWithoutListChanged() throws Exception {
            var initialize = new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                    .put("params", new JsonObject().put("protocolVersion", "2025-11-25")
                            .put("capabilities", new JsonObject())
                            .put("clientInfo", new JsonObject().put("name", "claude-code").put("version", "1")));

            var response = UdsHttp.request(TestRuntime.paths().socket(), "POST", "/mcp",
                    TestRuntime.mcpHeaders(runtime(), initialize), initialize.encode());

            var capabilities = capabilities(response);
            assertEquals(Boolean.FALSE, capabilities.getJsonObject("tools").getValue("listChanged"));
            assertEquals("2025-11-25", new JsonObject(response.body()).getJsonObject("result")
                    .getString("protocolVersion"));
            assertNull(response.headers().get("mcp-session-id"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"server/discover", "initialize", "tools/list"})
        @DisplayName("leaves the refusal of a 2026-07-28 request without the Mcp-Method header to the transport")
        void shouldLeaveStatelessRequestWithoutMethodHeaderToTransport(String method) throws Exception {
            var message = TestRuntime.statelessMessage(7, method, new JsonObject());

            var response = withoutMethodHeader(message);

            assertEquals(400, response.status(), response.body());
        }

        @ParameterizedTest(name = "protocol data in _meta: {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("answers subscriptions/listen without the Mcp-Method header with JSON-RPC error -32601")
        void shouldRefuseListenWithoutMethodHeader(boolean stateless) throws Exception {
            var params = new JsonObject().put("notifications", new JsonObject().put("toolsListChanged", true));
            var message = stateless ? TestRuntime.statelessMessage(8, LISTEN, params) : legacy(8, LISTEN, params);

            var response = withoutMethodHeader(message);

            assertEquals(200, response.status(), response.body());
            var answer = new JsonObject(response.body());
            assertEquals(8, answer.getInteger("id"));
            assertEquals(-32601, answer.getJsonObject("error").getInteger("code"));
        }

        @Test
        @DisplayName("announces tools without listChanged in an initialize without the Mcp-Method header")
        void shouldInitializeWithoutListChangedWithoutMethodHeader() throws Exception {
            var response = withoutMethodHeader(legacy(9, "initialize", INITIALIZE_PARAMS));

            assertEquals(Boolean.FALSE, capabilities(response).getJsonObject("tools").getValue("listChanged"));
        }

        @Test
        @DisplayName("leaves other methods without the Mcp-Method header to the MCP server")
        void shouldPassOtherMethodsWithoutMethodHeader() throws Exception {
            var response = withoutMethodHeader(legacy(10, "tools/list", new JsonObject()));

            assertEquals(200, response.status(), response.body());
            assertFalse(TestRuntime.result(response.body()).getJsonObject("result").getJsonArray("tools").isEmpty());
        }

        @Test
        @DisplayName("leaves a body that is no JSON-RPC request to the MCP server")
        void shouldPassUnreadableBodyWithoutMethodHeader() throws Exception {
            var headers = new HashMap<>(TestRuntime.mcpHeaders(runtime(), legacy(11, "tools/list", new JsonObject())));
            headers.remove(NotificationStreamFilter.METHOD_HEADER);

            var response = UdsHttp.request(TestRuntime.paths().socket(), "POST", "/mcp", headers, "{\"method\": 5}");

            assertFalse(response.status() == 200 && response.body().contains("\"result\""), response.body());
        }

        @Test
        @DisplayName("leaves other methods to the MCP server")
        void shouldPassOtherMethods() throws Exception {
            var response = TestRuntime.mcp(TestRuntime.paths(), runtime(),
                    TestRuntime.statelessMessage(2, "tools/list", new JsonObject()));

            assertEquals(200, response.status(), response.body());
            var tools = TestRuntime.result(response.body()).getJsonObject("result").getJsonArray("tools");
            assertFalse(tools.isEmpty());
        }
    }

    @Nested
    @DisplayName("message rewriting")
    class Rewriting {

        private static JsonObject result(JsonObject capabilities) {
            return new JsonObject().put("jsonrpc", "2.0").put("id", 1)
                    .put("result", new JsonObject().put("capabilities", capabilities));
        }

        @Test
        @DisplayName("sets listChanged of tools, resources and prompts to false where declared")
        void shouldClearDeclaredListChanged() {
            var message = result(new JsonObject()
                    .put("tools", new JsonObject().put("listChanged", true))
                    .put("resources", new JsonObject().put("listChanged", true).put("subscribe", true))
                    .put("prompts", new JsonObject())
                    .put("logging", new JsonObject()));

            var capabilities = NotificationStreamFilter.withoutListChanged(message).getJsonObject("result")
                    .getJsonObject("capabilities");

            assertEquals(false, capabilities.getJsonObject("tools").getBoolean("listChanged"));
            assertEquals(false, capabilities.getJsonObject("resources").getBoolean("listChanged"));
            assertEquals(true, capabilities.getJsonObject("resources").getBoolean("subscribe"));
            assertFalse(capabilities.getJsonObject("prompts").containsKey("listChanged"));
        }

        @Test
        @DisplayName("rewrites an SSE message event and keeps its framing")
        void shouldRewriteEvent() {
            var message = result(new JsonObject().put("tools", new JsonObject().put("listChanged", true)));

            var event = NotificationStreamFilter.rewriteEvent("event: message\ndata: " + message.encode() + "\n\n");

            assertEquals("event: message\ndata: " + result(new JsonObject().put("tools",
                    new JsonObject().put("listChanged", false))).encode() + "\n\n", event);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"event: ping\ndata: {}\n\n", "event: message\ndata: [1]\n\n",
                "event: message\ndata: not json\n\n", "event: message\ndata: {}"})
        @DisplayName("leaves other events unchanged")
        void shouldKeepOtherEvents(String event) {
            assertSame(event, NotificationStreamFilter.rewriteEvent(event));
        }

        @Test
        @DisplayName("reads the method of a JSON-RPC request body")
        void shouldReadMethodOfBody() {
            assertEquals("initialize", NotificationStreamFilter.methodOf(legacy(1, "initialize", new JsonObject()).toBuffer()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"not json", "[{\"method\":\"initialize\"}]", "{\"method\":5}", "{\"id\":1}", ""})
        @DisplayName("reads no method from a body that is no JSON-RPC request")
        void shouldReadNoMethod(String body) {
            assertNull(NotificationStreamFilter.methodOf(Buffer.buffer(body)));
        }

        @Test
        @DisplayName("rewrites a JSON body and leaves a body without JSON object or capabilities unchanged")
        void shouldRewriteBody() {
            var body = result(new JsonObject().put("tools", new JsonObject().put("listChanged", true))).toBuffer();
            var plain = Buffer.buffer("not json");
            var array = new JsonArray().add(1).toBuffer();
            var error = NotificationStreamFilter.refusal("x").toBuffer();

            assertEquals(false, new JsonObject(NotificationStreamFilter.rewriteBody(body)).getJsonObject("result")
                    .getJsonObject("capabilities").getJsonObject("tools").getBoolean("listChanged"));
            assertSame(plain, NotificationStreamFilter.rewriteBody(plain));
            assertSame(array, NotificationStreamFilter.rewriteBody(array));
            assertEquals(new JsonObject(error), new JsonObject(NotificationStreamFilter.rewriteBody(error)));
        }
    }
}
