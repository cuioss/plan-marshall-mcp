/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;


import de.planmarshall.mcp.server.test.DaemonProcess;
import de.planmarshall.mcp.server.test.TestRuntime;
import de.planmarshall.mcp.server.test.TestSecrets;
import de.planmarshall.mcp.server.test.UdsHttp;
import de.planmarshall.mcp.server.test.VerificationResults;
import de.planmarshall.runtime.test.TestBases;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate 15 and the event stream against the packaged daemon (JVM runner or native binary): the web listener
 * opened and closed at runtime beside a running SSE stream on the socket, its route restriction, the refusal of
 * every credential but an enrolled device secret (the packaged daemon has no device yet, so the test's device
 * secret is refused too), and TLS in LAN mode.
 */
@DisplayName("Web listener and event stream of the packaged daemon")
class WebListenerIT {

    private static Path base;
    private static DaemonProcess daemon;

    @BeforeAll
    static void start() throws IOException {
        base = TestBases.create("pmw");
        daemon = DaemonProcess.startReady(base);
    }

    @AfterAll
    static void stop() throws IOException {
        daemon.close();
        TestBases.delete(base);
    }

    /** The daemon's own output, which names the reason when it refuses to open the listener. */
    private static String daemonLog() {
        return "daemon log:\n" + daemon.output();
    }

    /** An open LAN listener on the port it bound; closing it closes the listener again. */
    private record LanListener(int port) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            assertEquals(200, putWeb("{\"enabled\":false}").status());
        }
    }

    /** Opens the LAN listener on a port of its own choice (port 0). */
    private static LanListener lanEnabled() throws IOException {
        var opened = putWeb("{\"enabled\":true,\"lan\":true,\"port\":0}");
        assertEquals(200, opened.status(), WebListenerIT::daemonLog);
        return new LanListener(new JsonObject(opened.body()).getInteger("port"));
    }

    private static UdsHttp.Response putWeb(String body) throws IOException {
        var headers = TestRuntime.bearer(daemon.token());
        headers.put("Content-Type", "application/json");
        return UdsHttp.request(daemon.paths().socket(), "PUT", "/api/v1/web", headers, body);
    }

    private static int web(int port, String method, String path, Map<String, String> extra) throws IOException {
        var headers = new HashMap<>(Map.of("Host", "127.0.0.1:" + port));
        headers.putAll(extra);
        return UdsHttp.request(new InetSocketAddress("127.0.0.1", port), method, path, headers,
                "POST".equals(method) ? "{}" : null).status();
    }

    private static int nextHeartbeat(UdsHttp.Stream stream) throws IOException {
        for (var line = stream.readLine(); line != null; line = stream.readLine()) {
            if (line.startsWith("data:")) {
                return new JsonObject(line.substring(5).strip()).getInteger("seq");
            }
        }
        throw new IOException("event stream ended");
    }

    @Test
    @DisplayName("opens and closes the web listener while an SSE stream on the socket keeps running")
    void shouldOpenAndCloseBesideStream() throws Exception {
        var device = Map.of("Authorization", "Bearer " + TestSecrets.DEVICE_SECRET);
        var started = System.nanoTime();
        try (var events = UdsHttp.open(daemon.paths().socket(), "GET", "/api/v1/events",
                     TestRuntime.bearer(daemon.token()), null)) {
            assertEquals(0, nextHeartbeat(events));
            var firstEventMillis = (System.nanoTime() - started) / 1_000_000;
            assertTrue(firstEventMillis < 900, "first event after " + firstEventMillis + " ms");

            var openStarted = System.nanoTime();
            var opened = putWeb("{\"enabled\":true,\"lan\":false,\"port\":0}");
            var openMillis = (System.nanoTime() - openStarted) / 1_000_000;
            assertEquals(200, opened.status(), WebListenerIT::daemonLog);
            int port = new JsonObject(opened.body()).getInteger("port");

            assertEquals(401, web(port, "GET", "/api/v1/status", device));
            assertEquals(401, web(port, "GET", "/api/v1/status", Map.of("Authorization",
                    "Bearer " + daemon.token())));
            assertEquals(404, web(port, "POST", "/mcp", Map.of("PM-MCP-Job-Token", TestSecrets.JOB_TOKEN)));
            assertEquals(404, web(port, "POST", "/mcp", device));
            assertEquals(403, web(port, "GET", "/api/v1/status", Map.of("Host", "rebound.example:" + port,
                    "Authorization", "Bearer " + TestSecrets.DEVICE_SECRET)));
            assertEquals(200, web(port, "GET", "/", Map.of()));
            var afterOpen = nextHeartbeat(events);

            var closeStarted = System.nanoTime();
            assertEquals(200, putWeb("{\"enabled\":false,\"port\":" + port + "}").status());
            var closeMillis = (System.nanoTime() - closeStarted) / 1_000_000;
            assertThrows(ConnectException.class, () -> web(port, "GET", "/api/v1/status", device));
            var afterClose = nextHeartbeat(events);

            assertTrue(afterClose > afterOpen);
            var values = new LinkedHashMap<String, Object>();
            values.put("first_event_ms", firstEventMillis);
            values.put("open_ms", openMillis);
            values.put("close_ms", closeMillis);
            values.put("socket_stream_survived", true);
            VerificationResults.write("gate15-web-listener", values, true);
            VerificationResults.write("gate1-sse-unbuffered", Map.of("first_event_ms", firstEventMillis),
                    firstEventMillis < 900);
        }
    }

    @Test
    @DisplayName("serves HTTPS with the self-signed ECDSA certificate in LAN mode")
    void shouldServeTls() throws Exception {
        try (var listener = lanEnabled()) {
            var port = listener.port();
            var pem = Files.readString(daemon.paths().base().resolve("web/tls/cert.pem"));
            var certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            var trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("pm-mcpd", certificate);
            var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            var ssl = SSLContext.getInstance("TLS");
            ssl.init(null, factory.getTrustManagers(), null);
            try (var client = HttpClient.newBuilder().sslContext(ssl).build()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port
                                + "/")).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode(), response.body());
                VerificationResults.write("gate15-web-tls", Map.of("status", response.statusCode(),
                                "signature_algorithm", ((X509Certificate) certificate).getSigAlgName()),
                        true);
            }
        }
    }

    @Test
    @DisplayName("refuses LAN mode on a port another listener holds on loopback")
    void shouldRefuseLanOnPortHeldOnLoopback() throws Exception {
        try (var foreign = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var response = putWeb("{\"enabled\":true,\"lan\":true,\"port\":" + foreign.getLocalPort() + "}");

            assertEquals(409, response.status(), WebListenerIT::daemonLog);
            assertEquals("web_listener_conflict", new JsonObject(response.body()).getString("code"));
        } finally {
            putWeb("{\"enabled\":false}");
        }
    }
}
