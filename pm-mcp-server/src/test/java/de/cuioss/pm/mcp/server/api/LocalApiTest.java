/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.pm.mcp.server.test.TestRuntime;
import de.cuioss.pm.mcp.server.test.TestSecrets;
import de.cuioss.pm.mcp.server.test.UdsHttp;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
@DisplayName("Local API on the Unix socket")
class LocalApiTest {

    /** Records whether the socket existed when the StartupEvent observers ran. */
    @ApplicationScoped
    static class StartupProbe {

        static final AtomicReference<Boolean> SOCKET_AT_STARTUP = new AtomicReference<>();

        void onStart(@Observes StartupEvent event) {
            SOCKET_AT_STARTUP.set(Files.exists(TestRuntime.paths().socket(), LinkOption.NOFOLLOW_LINKS));
        }
    }

    private static UdsHttp.Response get(String path, Map<String, String> headers) throws IOException {
        return UdsHttp.request(TestRuntime.paths().socket(), "GET", path, headers, null);
    }

    @Test
    @DisplayName("StartupEvent observers run before the HTTP server binds the socket")
    void shouldObserveStartupBeforeBind() {
        assertEquals(Boolean.FALSE, StartupProbe.SOCKET_AT_STARTUP.get());
    }

    @Test
    @DisplayName("the bound socket has mode 0600 and the runtime record names it")
    void shouldSecureSocketAndRecord() throws Exception {
        var paths = TestRuntime.paths();
        var deadline = System.currentTimeMillis() + 5000;
        while (!Files.exists(paths.runtimeRecord()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(paths.socket(), LinkOption.NOFOLLOW_LINKS)));
        var record = new JsonObject(Files.readString(paths.runtimeRecord()));
        assertEquals(paths.socket().toString(), record.getString("socket_path"));
        assertEquals("unix", record.getString("listener"));
        assertEquals(ProcessHandle.current().pid(), record.getLong("pid"));
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.runtimeRecord())));
    }

    @Test
    @DisplayName("GET /api/v1/status answers the runtime status")
    void shouldAnswerStatus() throws Exception {
        var response = get("/api/v1/status", TestRuntime.bearer(TestRuntime.token()));

        assertEquals(200, response.status(), response.body());
        var status = new JsonObject(response.body());
        assertEquals("unix", status.getString("listener"));
        assertEquals(TestRuntime.paths().socket().toString(), status.getString("socket_path"));
        assertEquals(ProcessHandle.current().pid(), status.getLong("pid"));
        assertTrue(status.containsKey("version"));
        assertTrue(status.containsKey("started_at"));
        assertEquals(new JsonObject().put("enabled", false).put("lan", false).put("port", 7420).put("open", false),
                status.getJsonObject("web"));
    }

    @ParameterizedTest(name = "Authorization: {0}")
    @ValueSource(strings = {"", "Bearer wrong-token", "Bearer " + TestSecrets.DEVICE_SECRET, "Basic dXNlcjpwdw=="})
    @DisplayName("refuses a request without the runtime token with 401")
    void shouldRefuseWithoutToken(String authorization) throws Exception {
        var headers = authorization.isEmpty() ? Map.<String, String>of() : Map.of("Authorization", authorization);

        assertEquals(401, get("/api/v1/status", headers).status());
        assertEquals(401, get("/no/such/path", headers).status());
    }

    @Test
    @DisplayName("refuses a job token on /api/v1")
    void shouldRefuseJobToken() throws Exception {
        assertEquals(401, get("/api/v1/status", Map.of("PM-MCP-Job-Token", TestSecrets.JOB_TOKEN)).status());
    }

    @Test
    @DisplayName("GET /api/v1/events sends the first heartbeat at once, before the stream ends")
    void shouldStreamUnbuffered() throws Exception {
        var started = System.nanoTime();
        try (var stream = UdsHttp.open(TestRuntime.paths().socket(), "GET", "/api/v1/events",
                     TestRuntime.bearer(TestRuntime.token()), null)) {
            assertEquals(200, stream.status());
            assertTrue(stream.headers().get("content-type").startsWith("text/event-stream"));
            String event = null;
            String data = null;
            while (data == null) {
                var line = stream.readLine();
                if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data = line.substring(5).strip();
                }
            }
            var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertEquals("heartbeat", event);
            assertEquals(0, new JsonObject(data).getInteger("seq"));
            assertTrue(elapsedMillis < 900, "first event after " + elapsedMillis + " ms");
        }
    }

    @Test
    @DisplayName("PUT /api/v1/web refuses a body without enabled")
    void shouldRefuseIncompleteWebSetting() throws Exception {
        var headers = TestRuntime.bearer(TestRuntime.token());
        headers.put("Content-Type", "application/json");

        var response = UdsHttp.request(TestRuntime.paths().socket(), "PUT", "/api/v1/web", headers, "{}");

        assertEquals(400, response.status());
        assertEquals("invalid_arguments", new JsonObject(response.body()).getString("code"));
        assertFalse(response.body().isBlank());
    }
}
