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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


import de.cuioss.pm.mcp.server.test.TestRuntime;
import de.cuioss.pm.mcp.server.test.UdsHttp;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins what the runtime relies on in quarkus-mcp-server: a host's {@code subscriptions/listen} is acknowledged at
 * once and its stream stays open, and the runtime sends nothing on it
 * (doc/specification/runtime-model/03-relay.adoc, Host Protocol Forms).
 */
@QuarkusTest
@DisplayName("A subscriptions/listen stream is accepted and stays silent")
class ListenStreamTest {

    private static final String END_OF_STREAM = "<end of stream>";
    private static final String DATA = "data:";

    private static Map<String, String> runtime() {
        return TestRuntime.bearer(TestRuntime.token());
    }

    /** Reads the stream on its own thread, so the test can wait for a line with a bound. */
    private static BlockingQueue<String> dataLines(UdsHttp.Stream stream) {
        var lines = new LinkedBlockingQueue<String>();
        Thread.ofVirtual().start(() -> {
            try {
                for (var line = stream.readLine(); line != null; line = stream.readLine()) {
                    if (line.startsWith(DATA)) {
                        lines.add(line.substring(DATA.length()).strip());
                    }
                }
            } catch (IOException _) {
                // the test closed the stream
            }
            lines.add(END_OF_STREAM);
        });
        return lines;
    }

    private static String hello(int id, String name) throws IOException {
        var response = TestRuntime.mcp(TestRuntime.paths(), runtime(), TestRuntime.statelessMessage(id, "tools/call",
                new JsonObject().put("name", "hello").put("arguments", new JsonObject().put("name", name))));
        assertEquals(200, response.status(), response.body());
        return TestRuntime.result(response.body()).getJsonObject("result").getJsonArray("content").getJsonObject(0)
                .getString("text");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"server/discover", "initialize"})
    @DisplayName("answers the opening request of both host forms with its capabilities")
    void shouldAnswerOpeningRequest(String method) throws Exception {
        var message = "initialize".equals(method)
                ? new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", method)
                .put("params", new JsonObject().put("protocolVersion", "2025-11-25")
                        .put("capabilities", new JsonObject())
                        .put("clientInfo", new JsonObject().put("name", "opencode").put("version", "1")))
                : TestRuntime.statelessMessage(1, method, new JsonObject());

        var response = UdsHttp.request(TestRuntime.paths().socket(), "POST", "/mcp",
                TestRuntime.mcpHeaders(runtime(), message), message.encode());

        assertEquals(200, response.status(), response.body());
        assertNull(response.headers().get("mcp-session-id"));
        assertNotNull(TestRuntime.result(response.body()).getJsonObject("result").getJsonObject("capabilities")
                .getJsonObject("tools"), response.body());
    }

    @Test
    @DisplayName("acknowledges the listen, serves tool calls beside it and sends nothing more on its stream")
    void shouldKeepListenStreamSilent() throws Exception {
        var listen = TestRuntime.statelessMessage(5, "subscriptions/listen",
                new JsonObject().put("notifications", new JsonObject().put("toolsListChanged", true)));

        try (var stream = UdsHttp.open(TestRuntime.paths().socket(), "POST", "/mcp",
                     TestRuntime.mcpHeaders(runtime(), listen), listen.encode())) {
            var lines = dataLines(stream);
            assertEquals(200, stream.status());
            assertTrue(stream.headers().get("content-type").startsWith("text/event-stream"),
                    stream.headers().toString());
            var acknowledgement = lines.poll(10, TimeUnit.SECONDS);
            assertNotNull(acknowledgement, "no acknowledgement of the listen");
            assertEquals("notifications/subscriptions/acknowledged",
                    new JsonObject(acknowledgement).getString("method"));

            assertEquals("Hello, first!", hello(6, "first"));
            assertEquals("Hello, second!", hello(7, "second"));

            assertNull(lines.poll(500, TimeUnit.MILLISECONDS), "a message on the listen stream");
        }
    }
}
