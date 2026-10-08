/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import de.planmarshall.api.http.HttpRequest;
import de.planmarshall.api.testsupport.FakeRuntimeServer;
import de.planmarshall.api.testsupport.RuntimeFixture;

@DisplayName("RuntimeClient")
@EnabledOnOs({OS.MAC, OS.LINUX})
class RuntimeClientTest {

    private static final FakeRuntimeServer.Handler OK = (request, response) -> response.json(200, "{\"ok\":true}");

    private RuntimeFixture fixture;
    private int uid;

    @BeforeEach
    void setUp() throws IOException {
        fixture = RuntimeFixture.create();
        fixture.writeToken("token-1");
        uid = (Integer) Files.getAttribute(fixture.paths().runtimeToken(), "unix:uid", LinkOption.NOFOLLOW_LINKS);
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    private RuntimeClient client(RuntimeStarter starter) {
        return RuntimeClient.local(fixture.paths(), new RuntimeTokenFile(fixture.paths(), uid), starter,
                Map.of("User-Agent", "test"));
    }

    @Nested
    @DisplayName("Runtime token")
    class Token {

        @Test
        @DisplayName("sends the runtime token and the fixed headers")
        void sendsToken() throws Exception {
            var server = fixture.start(OK);

            var response = client(null).send(HttpRequest.get("/api/v1/status"));

            assertEquals(200, response.status());
            var request = server.requests().getFirst();
            assertEquals("Bearer token-1", request.header("Authorization"));
            assertEquals("test", request.header("User-Agent"));
        }

        @Test
        @DisplayName("re-reads the token once after a 401 (runtime restarted) and succeeds")
        void rereadsOnce() throws Exception {
            var server = fixture.start(OK);
            var client = client(null);
            client.send(HttpRequest.get("/a"));
            fixture.writeToken("token-2");

            assertEquals(200, client.send(HttpRequest.get("/b")).status());
            assertEquals(3, server.requests().size());
        }

        @Test
        @DisplayName("does not retry a second 401")
        void secondUnauthorized() throws Exception {
            var server = fixture.start(OK);
            fixture.acceptToken("other");

            assertThrows(UnauthorizedException.class, () -> client(null).send(HttpRequest.get("/a")));
            assertEquals(2, server.requests().size());
        }

        @Test
        @DisplayName("sends nothing when the token check refuses")
        void refusesInsecure() throws Exception {
            var server = fixture.start(OK);
            Files.setPosixFilePermissions(fixture.paths().runtimeToken(), PosixFilePermissions.fromString("rw-rw-rw-"));

            assertThrows(InsecureRuntimeFileException.class, () -> client(() -> {
            }).send(HttpRequest.get("/a")));
            assertThrows(InsecureRuntimeFileException.class, () -> client(null).isLive());
            assertTrue(server.requests().isEmpty());
        }
    }

    @Nested
    @DisplayName("Job token")
    class Job {

        @Test
        @DisplayName("sends the job token and never retries a 401")
        void jobToken() throws Exception {
            var server = fixture.start(OK);
            fixture.acceptJobToken("job-secret");
            var job = RuntimeAccess.job(fixture.paths().socket(), "job-secret", Map.of());
            var wrong = RuntimeAccess.job(fixture.paths().socket(), "wrong", Map.of());

            assertEquals(200, job.send(HttpRequest.get("/mcp")).status());
            assertEquals("job-secret", server.requests().getFirst().header("PM-MCP-Job-Token"));
            assertThrows(UnauthorizedException.class, () -> wrong.send(HttpRequest.get("/mcp")));
            assertEquals(2, server.requests().size());
        }
    }

    @Nested
    @DisplayName("Without a runtime")
    class NoRuntime {

        @Test
        @DisplayName("reports the runtime unavailable when it may not start one")
        void noStart() {
            assertThrows(RuntimeUnavailableException.class, () -> client(null).send(HttpRequest.get("/a")));
        }

        @Test
        @DisplayName("starts the runtime once and then sends the request")
        void startsOnDemand() throws Exception {
            var starts = new AtomicInteger();
            var client = client(() -> {
                starts.incrementAndGet();
                fixture.writeToken("token-new");
                fixture.start(OK);
            });

            assertEquals(200, client.send(HttpRequest.get("/a")).status());
            assertEquals(1, starts.get());
        }

        @Test
        @DisplayName("starts on demand when no token exists yet and gives up after one start")
        void noTokenYet() throws Exception {
            Files.delete(fixture.paths().runtimeToken());
            var starts = new AtomicInteger();
            var client = client(starts::incrementAndGet);

            assertThrows(RuntimeUnavailableException.class, () -> client.send(HttpRequest.get("/a")));
            assertEquals(1, starts.get());
        }

        @Test
        @DisplayName("is not live without a listener or token, and live with one")
        void liveness() throws Exception {
            var client = client(null);
            assertFalse(client.isLive());

            fixture.start(OK);
            assertTrue(client.isLive());

            fixture.writeToken("token-3");
            assertFalse(client.isLive());
            assertTrue(client.isLive());

            Files.delete(fixture.paths().runtimeToken());
            assertFalse(client(null).isLive());
        }
    }
}
