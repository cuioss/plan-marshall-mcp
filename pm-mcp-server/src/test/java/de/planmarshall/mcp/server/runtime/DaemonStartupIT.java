/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;


import de.planmarshall.api.MachinePaths;
import de.planmarshall.mcp.server.test.BaseSnapshot;
import de.planmarshall.mcp.server.test.DaemonProcess;
import de.planmarshall.mcp.server.test.TestBases;
import de.planmarshall.mcp.server.test.TestRuntime;
import de.planmarshall.mcp.server.test.TestSecrets;
import de.planmarshall.mcp.server.test.UdsHttp;
import de.planmarshall.mcp.server.test.VerificationResults;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The startup sequence of the packaged daemon (JVM runner or native binary): singleton, socket and token modes,
 * authentication, stale-socket cleanup, the refused start, and stop.
 */
@DisplayName("Startup sequence of the packaged daemon")
class DaemonStartupIT {

    private static final Duration READY = Duration.ofSeconds(30);

    private Path base;

    @BeforeEach
    void createBase() {
        base = TestBases.create("pmd");
    }

    @AfterEach
    void deleteBase() {
        TestBases.delete(base);
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    @DisplayName("binds a 0600 socket in a 0700 run/, writes the runtime record, and refuses requests without the token")
    void shouldBindPrivateSocket() throws Exception {
        try (var daemon = DaemonProcess.startReady(base)) {
            var paths = daemon.paths();
            var socket = paths.socket();
            // the mode is set right after the bind; the runtime record is written after it
            var deadline = System.nanoTime() + READY.toNanos();
            while (!Files.exists(paths.runtimeRecord()) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertEquals("rw-------", mode(socket));
            assertEquals("rwx------", mode(paths.runDir()));
            assertEquals("rw-------", mode(paths.runtimeToken()));
            assertEquals("rw-------", mode(paths.runtimeRecord()));
            var record = new JsonObject(Files.readString(paths.runtimeRecord()));
            assertEquals(daemon.process().pid(), record.getLong("pid"));
            assertEquals(socket.toString(), record.getString("socket_path"));
            assertEquals("unix", record.getString("listener"));

            var status = UdsHttp.request(socket, "GET", "/api/v1/status", TestRuntime.bearer(daemon.token()), null);
            assertEquals(200, status.status(), status.body());
            for (var refused : List.<Map<String, String>>of(Map.of(), TestRuntime.bearer("wrong"),
                    TestRuntime.bearer(TestSecrets.DEVICE_SECRET), Map.of("PM-MCP-Job-Token", TestSecrets.JOB_TOKEN))) {
                assertEquals(401, UdsHttp.request(socket, "GET", "/api/v1/status", refused, null).status());
            }
            assertEquals(401, TestRuntime.mcp(paths, TestRuntime.bearer("wrong"),
                    TestRuntime.statelessMessage(1, "tools/list", new JsonObject())).status());
            VerificationResults.write("gate1-socket-listener", Map.of("socket_mode", mode(socket),
                    "run_mode", mode(paths.runDir()), "token_mode", mode(paths.runtimeToken())), true);
        }
    }

    @Test
    @DisplayName("a second daemon exits 0 at once and leaves the base byte-identical")
    void shouldLeaveBaseUntouchedOnSecondStart() throws Exception {
        try (var first = DaemonProcess.startReady(base)) {
            var deadline = System.nanoTime() + READY.toNanos();
            while (!Files.exists(first.paths().runtimeRecord()) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            var before = BaseSnapshot.of(base);

            try (var second = DaemonProcess.start(base, List.of())) {
                assertEquals(0, second.awaitExit(READY), second.output());
            }

            assertEquals(before, BaseSnapshot.of(base));
            assertTrue(DaemonProcess.accepts(first.paths().socket()));
        }
    }

    @Test
    @DisplayName("removes the stale socket and runtime record of a killed daemon")
    void shouldCleanUpAfterKill() throws Exception {
        long killedPid;
        String killedToken;
        try (var killed = DaemonProcess.startReady(base)) {
            killedPid = killed.process().pid();
            killedToken = killed.token();
            killed.kill();
        }
        var paths = new MachinePaths(base, MachinePaths.Os.current());
        assertTrue(Files.exists(paths.socket(), LinkOption.NOFOLLOW_LINKS), "the kill leaves the socket behind");
        assertFalse(DaemonProcess.accepts(paths.socket()));

        try (var restarted = DaemonProcess.startReady(base)) {
            assertNotEquals(killedToken, restarted.token());
            var status = UdsHttp.request(paths.socket(), "GET", "/api/v1/status",
                    TestRuntime.bearer(restarted.token()), null);
            assertEquals(200, status.status());
            assertNotEquals(killedPid, new JsonObject(status.body()).getLong("pid"));
            assertEquals(401, UdsHttp.request(paths.socket(), "GET", "/api/v1/status",
                    TestRuntime.bearer(killedToken), null).status());
        }
    }

    @Test
    @DisplayName("refuses an over-long socket path with exit code 77, writing no token or socket")
    void shouldRefuseLongBase() throws Exception {
        var longBase = base.resolve("b".repeat(MachinePaths.Os.current().sunPathLimit()));
        try (var daemon = DaemonProcess.start(longBase, List.of())) {
            var exit = daemon.awaitExit(READY);

            var paths = new MachinePaths(longBase, MachinePaths.Os.current());
            assertEquals(77, exit, daemon.output());
            assertTrue(daemon.output().contains(paths.socket().toString()), daemon.output());
            assertTrue(daemon.output().contains("PM_MCP_BASE"), daemon.output());
            assertFalse(Files.exists(paths.runtimeToken()));
            assertFalse(Files.exists(paths.socket(), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(paths.runtimeRecord()));
            VerificationResults.write("gate1-sun-path-refusal", Map.of("exit_code", exit,
                    "socket_path_bytes", paths.socketPathBytes()), exit == 77);
        }
    }

    @Test
    @DisplayName("POST /api/v1/runtime/stop answers 202 and the daemon exits 0")
    void shouldStopOnRequest() throws Exception {
        try (var daemon = DaemonProcess.startReady(base)) {
            var response = UdsHttp.request(daemon.paths().socket(), "POST", "/api/v1/runtime/stop",
                    TestRuntime.bearer(daemon.token()), "");

            assertEquals(202, response.status());
            assertEquals(0, daemon.awaitExit(READY), daemon.output());
        }
    }
}
