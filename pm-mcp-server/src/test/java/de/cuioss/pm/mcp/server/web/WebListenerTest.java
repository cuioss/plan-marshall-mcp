/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;


import de.cuioss.pm.mcp.server.test.SpikeVerifyProfile;
import de.cuioss.pm.mcp.server.test.TestRuntime;
import de.cuioss.pm.mcp.server.test.TestSecrets;
import de.cuioss.pm.mcp.server.test.UdsHttp;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(SpikeVerifyProfile.class)
@DisplayName("Web listener beside the Unix socket (gate 15)")
class WebListenerTest {

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** An open LAN listener; closing it closes the listener again. */
    private interface LanListener extends AutoCloseable {
        @Override
        void close() throws IOException;
    }

    private static LanListener lanEnabled(int port) throws IOException {
        assertEquals(200, putWeb("{\"enabled\":true,\"lan\":true,\"port\":" + port + "}").status());
        return () -> assertEquals(200, putWeb("{\"enabled\":false}").status());
    }

    private static UdsHttp.Response putWeb(String body) throws IOException {
        var headers = TestRuntime.bearer(TestRuntime.token());
        headers.put("Content-Type", "application/json");
        return UdsHttp.request(TestRuntime.paths().socket(), "PUT", "/api/v1/web", headers, body);
    }

    private static UdsHttp.Response web(int port, String method, String path, Map<String, String> extra)
            throws IOException {
        var headers = new HashMap<>(Map.of("Host", "127.0.0.1:" + port));
        headers.putAll(extra);
        return UdsHttp.request(new InetSocketAddress("127.0.0.1", port), method, path, headers,
                "PUT".equals(method) || "POST".equals(method) ? "{}" : null);
    }

    private static Map<String, String> device() {
        return Map.of("Authorization", "Bearer " + TestSecrets.DEVICE_SECRET);
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
    @DisplayName("opens and closes on loopback at runtime, serves only its routes, and leaves the socket stream alone")
    void shouldOpenAndCloseLoopback() throws Exception {
        var port = freePort();
        try (var events = UdsHttp.open(TestRuntime.paths().socket(), "GET", "/api/v1/events",
                     TestRuntime.bearer(TestRuntime.token()), null)) {
            assertEquals(0, nextHeartbeat(events));

            var opened = putWeb("{\"enabled\":true,\"lan\":false,\"port\":" + port + "}");
            assertEquals(200, opened.status(), opened.body());
            assertEquals(new JsonObject().put("enabled", true).put("lan", false).put("port", port).put("open", true),
                    new JsonObject(opened.body()));

            var status = web(port, "GET", "/api/v1/status", device());
            assertEquals(200, status.status(), status.body());
            assertTrue(new JsonObject(status.body()).getJsonObject("web").getBoolean("open"));
            WebRequestHandler.SECURITY_HEADERS.forEach((name, value) -> assertEquals(value,
                    status.headers().get(name.toLowerCase(Locale.ROOT)), name));
            assertFalse(status.headers().keySet().stream().anyMatch(name -> name.startsWith("access-control-")));

            assertEquals(401, web(port, "GET", "/api/v1/status",
                    Map.of("Authorization", "Bearer " + TestRuntime.token())).status());
            assertEquals(401, web(port, "GET", "/api/v1/status", Map.of()).status());
            assertEquals(404, web(port, "POST", "/mcp", Map.of("PM-MCP-Job-Token", TestSecrets.JOB_TOKEN)).status());
            assertEquals(404, web(port, "POST", "/mcp", device()).status());
            assertEquals(404, web(port, "GET", "/api/v2/status", device()).status());
            assertEquals(400, web(port, "GET", "/api/v1/../v1/status", device()).status());
            assertEquals(403, web(port, "GET", "/api/v1/status", Map.of("Host", "evil.example:" + port,
                    "Authorization", "Bearer " + TestSecrets.DEVICE_SECRET)).status());
            assertEquals(403, web(port, "OPTIONS", "/api/v1/status", device()).status());
            assertEquals(403, web(port, "PUT", "/api/v1/web", Map.of("Origin", "http://evil.example",
                    "Authorization", "Bearer " + TestSecrets.DEVICE_SECRET)).status());
            var cliOnly = web(port, "PUT", "/api/v1/web", Map.of("Origin", "http://127.0.0.1:" + port,
                    "Authorization", "Bearer " + TestSecrets.DEVICE_SECRET, "Content-Type", "application/json"));
            assertEquals(403, cliOnly.status());
            assertEquals("cli_only", new JsonObject(cliOnly.body()).getString("code"));
            var app = web(port, "GET", "/plans/some-plan", Map.of());
            assertEquals(200, app.status());
            assertTrue(app.body().contains("<!doctype html>"));
            assertEquals("DENY", app.headers().get("x-frame-options"));

            assertEquals(409, putWeb("{\"enabled\":true,\"lan\":true,\"port\":" + port + "}").status());
            assertEquals(200, putWeb("{\"enabled\":true,\"lan\":false,\"port\":" + port + "}").status());

            var seq = nextHeartbeat(events);
            var closed = putWeb("{\"enabled\":false,\"port\":" + port + "}");
            assertEquals(200, closed.status());
            assertFalse(new JsonObject(closed.body()).getBoolean("open"));
            assertThrows(ConnectException.class, () -> web(port, "GET", "/api/v1/status", device()));

            assertTrue(nextHeartbeat(events) > seq);
        }
    }

    @Test
    @DisplayName("serves HTTPS with the self-signed certificate in LAN mode")
    void shouldServeLanOverTls() throws Exception {
        var port = freePort();
        try (var _ = lanEnabled(port)) {
            var pem = Files.readString(TestRuntime.paths().base().resolve("web/tls/cert.pem"));
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
                                + "/api/v1/status")).header("Authorization", "Bearer " + TestSecrets.DEVICE_SECRET).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode(), response.body());
                assertTrue(new JsonObject(response.body()).getJsonObject("web").getBoolean("lan"));
            }
        }
    }

    @Test
    @DisplayName("an occupied port leaves the listener closed with web_listener_conflict")
    void shouldRefuseOccupiedPort() throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var response = putWeb("{\"enabled\":true,\"port\":" + occupied.getLocalPort() + "}");

            assertEquals(409, response.status());
            assertEquals("web_listener_conflict", new JsonObject(response.body()).getString("code"));
        }
    }
}
