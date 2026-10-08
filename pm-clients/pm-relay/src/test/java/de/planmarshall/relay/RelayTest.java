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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import de.planmarshall.api.PmVersion;
import de.planmarshall.api.runtime.RuntimeAccess;
import de.planmarshall.api.runtime.RuntimeClient;
import de.planmarshall.api.runtime.RuntimeStarter;
import de.planmarshall.api.runtime.RuntimeTokenFile;
import de.planmarshall.api.runtime.RuntimeUnavailableException;
import de.planmarshall.api.testsupport.FakeRuntimeServer;
import de.planmarshall.api.testsupport.RuntimeFixture;

@DisplayName("Relay")
@EnabledOnOs({OS.MAC, OS.LINUX})
class RelayTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private RuntimeFixture fixture;
    private LineSink out;
    private LineSink err;
    private PipedOutputStream host;
    private Thread relayThread;
    private final AtomicInteger exit = new AtomicInteger(-1);

    @BeforeEach
    void setUp() throws IOException {
        fixture = RuntimeFixture.create();
        fixture.writeToken("token-1");
        out = new LineSink();
        err = new LineSink();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) {
            host.close();
        }
        if (relayThread != null) {
            relayThread.join(WAIT.toMillis());
        }
        fixture.close();
    }

    private RuntimeClient sessionClient(RuntimeStarter starter) throws IOException {
        var uid = (Integer) Files.getAttribute(fixture.paths().runtimeToken(), "unix:uid", LinkOption.NOFOLLOW_LINKS);
        return RuntimeClient.local(fixture.paths(), new RuntimeTokenFile(fixture.paths(), uid), starter, Map.of());
    }

    private void startRelay(RuntimeClient client, RelayMode mode) throws IOException {
        host = new PipedOutputStream();
        var in = new PipedInputStream(host, 65536);
        var relay = new Relay(client, mode, in, out.writer(), err.writer());
        relayThread = Thread.ofPlatform().start(() -> exit.set(relay.run()));
    }

    private void send(String json) throws IOException {
        host.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        host.flush();
    }

    @Nested
    @DisplayName("Session relay")
    class Session {

        @Test
        @DisplayName("forwards initialize with the connection metadata and writes the response as one line")
        void initialize() throws Exception {
            startCapturing((request, response) -> response.json(200,
                    "{\"jsonrpc\":\"2.0\",\"id\":\"init-1\",\n\"result\":{\"protocolVersion\":\"2025-11-25\"}}"));

            send("{\"jsonrpc\":\"2.0\",\"id\":\"init-1\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2025-11-25\",\"clientInfo\":{\"name\":\"opencode\",\"version\":\"1.0\"},\"capabilities\":{\"elicitation\":{}}}}");

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"init-1\",\"result\":{\"protocolVersion\":\"2025-11-25\"}}",
                    out.next(5));
            var request = awaitRequest();
            assertEquals("POST", request.method());
            assertEquals("/mcp", request.target());
            assertEquals("Bearer token-1", request.header("Authorization"));
            assertEquals("initialize", request.header("Mcp-Method"));
            assertNull(request.header("Mcp-Name"));
            assertEquals(PmVersion.current(), request.header("PM-MCP-Relay-Version"));
            assertEquals("2025-11-25", request.header("PM-MCP-Protocol-Version"));
            assertEquals("{\"name\":\"opencode\",\"version\":\"1.0\"}", request.header("PM-MCP-Client-Info"));
            assertEquals("{\"elicitation\":{}}", request.header("PM-MCP-Client-Capabilities"));
            assertEquals("claude", request.header("PM-MCP-Client"));
            assertEquals("/work/space", request.header("PM-MCP-Workspace"));
            assertNull(request.header("PM-MCP-Generation"));
            assertTrue(request.bodyText().contains("\"method\":\"initialize\""));
        }

        private FakeRuntimeServer server;

        private FakeRuntimeServer.Request awaitRequest() throws InterruptedException {
            return lastServer().awaitRequest(WAIT);
        }

        private FakeRuntimeServer lastServer() {
            return server;
        }

        @BeforeEach
        void capture() {
            server = null;
        }

        private void startCapturing(FakeRuntimeServer.Handler handler) throws IOException {
            server = fixture.start(handler);
            startRelay(sessionClient(null), RelayMode.session("claude", "/work/space"));
        }

        @Test
        @DisplayName("sends the recorded protocol data with later requests and unwraps an SSE response")
        void recordedAndSse() throws Exception {
            startCapturing((request, response) -> {
                if ("initialize".equals(request.header("Mcp-Method"))) {
                    response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
                    return;
                }
                try (var events = response.events(200)) {
                    events.send(null, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}");
                    events.send("message", "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"pm_wait\","
                            + "\n\"inputSchema\":{\"type\":\"object\",\"properties\":{\"generation_id\":{},\"role\":{},"
                            + "\"plan_id\":{\"type\":\"string\"}},\"required\":[\"plan_id\",\"worker\"]}},{\"name\":\"x\"}]}}");
                }
            });
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            assertNotNull(out.next(5));
            awaitRequest();

            send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

            assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}",
                    out.next(5));
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"pm_wait\",\"inputSchema\":"
                    + "{\"type\":\"object\",\"properties\":{\"plan_id\":{\"type\":\"string\"}},\"required\":[\"plan_id\"]}},"
                    + "{\"name\":\"x\"}]}}", out.next(5));
            var request = awaitRequest();
            assertEquals("tools/list", request.header("Mcp-Method"));
            assertEquals("2025-11-25", request.header("PM-MCP-Protocol-Version"));
        }

        @Test
        @DisplayName("takes the protocol data from params._meta and drops identity arguments of tools/call")
        void sessionlessCall() throws Exception {
            startCapturing((request, response) -> response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}"));

            send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"pm_state\",\"arguments\":"
                    + "{\"plan_id\":\"p-1\",\"generation_id\":\"invented\",\"job_token\":\"x\"},\"_meta\":{"
                    + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientInfo\":"
                    + "{\"name\":\"claude-code\"},\"io.modelcontextprotocol/clientCapabilities\":{\"x\":true}}}}");

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}", out.next(5));
            var request = awaitRequest();
            assertEquals("tools/call", request.header("Mcp-Method"));
            assertEquals("pm_state", request.header("Mcp-Name"));
            assertEquals("2026-07-28", request.header("PM-MCP-Protocol-Version"));
            assertEquals("{\"name\":\"claude-code\"}", request.header("PM-MCP-Client-Info"));
            assertEquals("{\"x\":true}", request.header("PM-MCP-Client-Capabilities"));
            assertTrue(request.bodyText().contains("\"arguments\":{\"plan_id\":\"p-1\"}"));
        }

        @Test
        @DisplayName("never answers a notification and forwards a host response")
        void notifications() throws Exception {
            startCapturing((request, response) -> response.json(200,
                    "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32601,\"message\":\"x\"}}"));

            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\"}");
            send("{\"jsonrpc\":\"2.0\",\"id\":\"s-1\",\"result\":{\"action\":\"accept\"}}");

            // Each message is forwarded on its own virtual thread, so the arrival order is not fixed.
            var methods = new ArrayList<String>();
            methods.add(awaitRequest().header("Mcp-Method"));
            methods.add(awaitRequest().header("Mcp-Method"));
            assertTrue(methods.contains("notifications/roots/list_changed"), methods::toString);
            assertTrue(methods.contains(null), methods::toString);
            assertNull(out.next(1));
        }

        @Test
        @DisplayName("drops the cancellation of an answered request and forwards that of a pending one")
        void cancellation() throws Exception {
            var release = new CountDownLatch(1);
            startCapturing((request, response) -> {
                var body = request.bodyText();
                if (body.contains("\"id\":1,")) {
                    response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
                } else if (body.contains("\"id\":2,")) {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}");
                } else {
                    response.send(202, null, "");
                }
            });
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
            assertNotNull(out.next(5));
            awaitRequest();
            send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
            awaitRequest();

            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}");
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":2}}");

            var cancel = awaitRequest();
            assertTrue(cancel.bodyText().contains("\"requestId\":2"));
            release.countDown();
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}", out.next(5));
            assertNull(server.awaitRequest(Duration.ofMillis(300)));
        }

        @Test
        @DisplayName("reports a status that carries no JSON-RPC response")
        void statusWithoutMessage() throws Exception {
            startCapturing((request, response) -> {
                if (request.bodyText().contains("\"id\":1,")) {
                    response.json(200, "no json");
                } else {
                    response.send(500, null, "");
                }
            });

            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\"}");
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"runtime_error: HTTP 200\"}}",
                    out.next(5));

            send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"b\"}");
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32603,\"message\":\"runtime_error: HTTP 500\"}}",
                    out.next(5));
        }

        @Test
        @DisplayName("writes batch elements and skips elements that are no objects")
        void batch() throws Exception {
            startCapturing((request, response) -> response.json(200,
                    "[{\"jsonrpc\":\"2.0\",\"method\":\"n\"},5,{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1}]"));

            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\"}");

            assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"n\"}", out.next(5));
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1}", out.next(5));
            assertNull(out.next(1));
        }

        @Test
        @DisplayName("answers a request at once when the stream to the runtime is lost")
        void lostStream() throws Exception {
            startCapturing((request, response) -> {
                var events = response.events(200);
                events.send(null, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}");
                events.chunk("data: {\"broken");
            });

            send("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"pm_wait\"}}");

            assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}", out.next(5));
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-32000,\"message\":\"runtime_unavailable\"}}",
                    out.next(5));
        }

        @Test
        @DisplayName("answers an invalid line with invalid_request and keeps relaying")
        void invalidLine() throws Exception {
            startCapturing((request, response) -> response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

            send("not json");
            send("[1,2]");

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"invalid_request\"}}",
                    out.next(5));
            assertNotNull(out.next(5));
            assertTrue(err.text().contains("pm-mcp serve: dropping a line"));
        }

        @Test
        @DisplayName("ends with exit code 0 when stdin closes")
        void endsOnEof() throws Exception {
            startCapturing((request, response) -> response.send(202, null, ""));

            host.close();
            relayThread.join(WAIT.toMillis());

            assertEquals(0, exit.get());
            assertEquals("", out.text());
        }
    }

    @Nested
    @DisplayName("Without a runtime")
    class NoRuntime {

        @Test
        @DisplayName("answers runtime_unavailable when the on-demand start fails and retries on the next request")
        void startFails() throws Exception {
            var starts = new AtomicInteger();
            startRelay(sessionClient(() -> {
                starts.incrementAndGet();
                throw new RuntimeUnavailableException("did not answer within 5000 ms");
            }), RelayMode.session("neutral", "/w"));

            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"runtime_unavailable\"}}",
                    out.next(5));
            send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
            assertNotNull(out.next(5));

            assertEquals(2, starts.get());
            assertTrue(err.text().contains("did not answer within 5000 ms"));
            assertFalse(err.text().contains("token-1"));
        }

        @Test
        @DisplayName("diagnoses a refused notification without writing to stdout")
        void notificationWithoutRuntime() throws Exception {
            startRelay(sessionClient(null), RelayMode.session("neutral", "/w"));

            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            var deadline = System.nanoTime() + WAIT.toNanos();
            while (!err.text().contains("not running") && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(err.text().contains("not running"));
            assertEquals("", out.text());
        }
    }

    @Nested
    @DisplayName("Worker relay")
    class Worker {

        @Test
        @DisplayName("sends the job token and the generation, and no session headers")
        void jobHeaders() throws Exception {
            var server = fixture.start((request, response) -> response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
            fixture.acceptJobToken("job-secret");
            startRelay(RuntimeAccess.job(fixture.paths().socket(), "job-secret", Map.of()), RelayMode.worker("job-42"));

            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

            assertNotNull(out.next(5));
            var request = server.awaitRequest(WAIT);
            assertEquals("job-secret", request.header("PM-MCP-Job-Token"));
            assertNull(request.header("Authorization"));
            assertEquals("job-42", request.header("PM-MCP-Generation"));
            assertNull(request.header("PM-MCP-Client"));
            assertNull(request.header("PM-MCP-Workspace"));
        }

        @Test
        @DisplayName("ends with a diagnostic when the runtime refuses the job token")
        void refused() throws Exception {
            fixture.start((request, response) -> response.json(200, "{}"));
            fixture.acceptJobToken("other");
            startRelay(RuntimeAccess.job(fixture.paths().socket(), "job-secret", Map.of()), RelayMode.worker("job-42"));

            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            relayThread.join(WAIT.toMillis());

            assertEquals(1, exit.get());
            assertTrue(err.text().contains("refused the job token"));
            assertFalse(err.text().contains("job-secret"));
        }

        @Test
        @DisplayName("ends with a diagnostic when a notification is refused")
        void refusedNotification() throws Exception {
            fixture.start((request, response) -> response.json(200, "{}"));
            startRelay(RuntimeAccess.job(fixture.paths().socket(), "job-secret", Map.of()), RelayMode.worker("job-42"));

            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            relayThread.join(WAIT.toMillis());

            assertEquals(1, exit.get());
        }
    }
}
