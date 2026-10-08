/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import de.planmarshall.api.testsupport.FakeRuntimeServer;

@DisplayName("UnixSocketHttpClient")
class UnixSocketHttpClientTest {

    private Path directory;
    private Path socket;
    private FakeRuntimeServer server;

    @BeforeEach
    void setUp() throws IOException {
        directory = Files.createTempDirectory(Path.of("/tmp"), "pmh");
        socket = directory.resolve("s.sock");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
        Files.deleteIfExists(socket);
        Files.deleteIfExists(directory);
    }

    private UnixSocketHttpClient serve(FakeRuntimeServer.Handler handler) throws IOException {
        server = FakeRuntimeServer.start(socket, handler);
        return new UnixSocketHttpClient(socket);
    }

    @Nested
    @DisplayName("Request/response")
    class RequestResponse {

        @Test
        @DisplayName("sends method, target, headers and body; reads a Content-Length body")
        void contentLength() throws Exception {
            var client = serve((request, response) -> response.json(200, "{\"echo\":\"" + request.bodyText() + "\"}"));

            var response = client.send(HttpRequest.json("PUT", "/api/v1/x", "abc").withHeader("X-A", "1"));

            assertEquals(200, response.status());
            assertEquals("application/json", response.contentType());
            assertEquals("{\"echo\":\"abc\"}", response.bodyText());
            var received = server.awaitRequest(Duration.ofSeconds(5));
            assertEquals("PUT", received.method());
            assertEquals("/api/v1/x", received.target());
            assertEquals("1", received.header("X-A"));
            assertEquals("3", received.header("Content-Length"));
            assertEquals("close", received.header("Connection"));
            assertEquals(socket, client.socket());
        }

        @Test
        @DisplayName("decodes a chunked body with extensions and trailers")
        void chunked() throws Exception {
            var body = "x".repeat(100) + "ü";
            var client = serve((request, response) -> response.chunked(200, "text/plain", body, 7));

            assertEquals(body, client.send(HttpRequest.get("/c")).bodyText());
        }

        @Test
        @DisplayName("reads a body until the connection closes when no framing is given")
        void untilClose() throws Exception {
            var client = serve((request, response) -> response.raw("HTTP/1.0 200 OK\r\nX: y\r\n\r\nrest"));

            try (var exchange = client.open(HttpRequest.get("/r"))) {
                assertEquals("y", exchange.header("x"));
                assertNull(exchange.header("missing"));
                assertFalse(exchange.isEventStream());
                assertEquals("rest", exchange.readBodyText());
            }
        }

        @Test
        @DisplayName("skips an interim 100 response and reads no body for 204")
        void interimAndNoContent() throws Exception {
            var client = serve((request, response) -> response.raw("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 204 No\r\n\r\n"));

            var response = client.send(HttpRequest.json("DELETE", "/d", ""));

            assertEquals(204, response.status());
            assertEquals(0, response.body().length);
        }

        @Test
        @DisplayName("sends Content-Length 0 for a POST without body and none for a GET")
        void contentLengthRules() {
            var post = new String(UnixSocketHttpClient.encode(new HttpRequest("POST", "/p", Map.of(), null)),
                    StandardCharsets.UTF_8);
            var get = new String(UnixSocketHttpClient.encode(HttpRequest.get("/g")), StandardCharsets.UTF_8);

            assertTrue(post.contains("Content-Length: 0\r\n"));
            assertFalse(get.contains("Content-Length"));
            assertTrue(get.startsWith("GET /g HTTP/1.1\r\nHost: localhost\r\n"));
        }
    }

    @Nested
    @DisplayName("Server-Sent Events")
    class Events {

        @Test
        @DisplayName("delivers each event before the next one is sent")
        void unbuffered() throws Exception {
            var released = new CountDownLatch(1);
            var client = serve((request, response) -> {
                try (var stream = response.events(200)) {
                    stream.send("heartbeat", "{\"seq\":0}");
                    if (!released.await(5, TimeUnit.SECONDS)) {
                        return;
                    }
                    stream.send(null, "a\nb");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            try (var exchange = client.open(HttpRequest.get("/api/v1/events"))) {
                assertTrue(exchange.isEventStream());
                var events = exchange.events();
                var first = events.next();
                assertEquals(new SseEvent("heartbeat", "{\"seq\":0}", null), first);
                released.countDown();
                assertEquals(new SseEvent("message", "a\nb", null), events.next());
                assertNull(events.next());
            }
        }
    }

    @Nested
    @DisplayName("Failures")
    class Failures {

        @Test
        @DisplayName("reports an absent socket as unreachable")
        void unreachable() {
            var client = new UnixSocketHttpClient(socket);

            var e = assertThrows(RuntimeUnreachableException.class, () -> client.send(HttpRequest.get("/")));
            assertTrue(e.getMessage().contains(socket.toString()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"garbage\r\n\r\n", "HTTP/1.1 abc X\r\n\r\n", "HTTP/1.1 200 OK\r\nbad header\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nabX\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: x\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort", "HTTP/1.1 200 OK\r\n"})
        @DisplayName("refuses a malformed response")
        void malformed(String raw) throws Exception {
            var client = serve((request, response) -> response.raw(raw));

            assertThrows(IOException.class, () -> client.send(HttpRequest.get("/m")));
        }

        @Test
        @DisplayName("refuses a header with a line break and a relative target")
        void requestValidation() {
            var request = HttpRequest.get("/");

            assertThrows(IllegalArgumentException.class, () -> request.withHeader("X", "a\r\nb"));
            assertThrows(IllegalArgumentException.class, () -> HttpRequest.get("relative"));
        }
    }

    @Nested
    @DisplayName("Value types")
    class Values {

        @Test
        @DisplayName("HttpRequest and HttpResponse copy their bodies and hide headers in toString")
        void values() {
            var body = "b".getBytes(StandardCharsets.UTF_8);
            var request = new HttpRequest("POST", "/x", Map.of("Authorization", "secret"), body);
            body[0] = 'z';

            assertArrayEquals("b".getBytes(StandardCharsets.UTF_8), request.body());
            assertEquals(request, new HttpRequest("POST", "/x", Map.of("Authorization", "secret"), request.body()));
            assertEquals(request.hashCode(),
                    new HttpRequest("POST", "/x", Map.of("Authorization", "secret"), request.body()).hashCode());
            assertNotEquals(request, HttpRequest.get("/x"));
            assertFalse(request.toString().contains("secret"));
            var response = new HttpResponse(200, null, null);
            assertEquals(response, new HttpResponse(200, null, new byte[0]));
            assertEquals(response.hashCode(), new HttpResponse(200, null, new byte[0]).hashCode());
            assertNotEquals(response, new HttpResponse(201, null, null));
            assertTrue(response.toString().contains("bytes=0"));
        }
    }
}
