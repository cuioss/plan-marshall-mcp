/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import de.cuioss.pm.api.http.HttpRequest;
import de.cuioss.pm.api.posix.PosixPlatform;
import de.cuioss.pm.api.testsupport.RuntimeFixture;

/**
 * The on-demand start end to end on the JVM: a staged {@code pm-mcpd} (a shell script reporting its
 * pid and process group) is started through FFM {@code posix_spawn}; the test brings up the fake
 * runtime when the script reports, and the client's request then succeeds.
 */
@DisplayName("Detached on-demand start (gate 8, JVM)")
@EnabledOnOs({OS.MAC, OS.LINUX})
class DetachedStartTest {

    private RuntimeFixture fixture;
    private Path home;
    private Path marker;
    private long daemonPid = -1;

    @BeforeEach
    void setUp() throws IOException {
        fixture = RuntimeFixture.create();
        home = fixture.base().resolveSibling("home");
        marker = fixture.base().resolveSibling("started");
        var daemon = Files.createDirectories(home.resolve("bin")).resolve("pm-mcpd");
        Files.writeString(daemon, """
                #!/bin/sh
                echo "daemon started"
                echo "$$ $(ps -o pgid= -p $$ | tr -d ' ')" > "$PM_TEST_MARKER.tmp"
                mv "$PM_TEST_MARKER.tmp" "$PM_TEST_MARKER"
                exec sleep 30
                """);
        daemon.toFile().setExecutable(true);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (daemonPid > 0) {
            ProcessHandle.of(daemonPid).ifPresent(ProcessHandle::destroyForcibly);
        }
        fixture.close();
    }

    private Map<String, String> environment() {
        var environment = fixture.environment();
        environment.put(PmMcpHome.ENV_HOME, home.toString());
        environment.put("PM_TEST_MARKER", marker.toString());
        environment.put("PATH", "/usr/bin:/bin");
        return environment;
    }

    @Test
    @DisplayName("starts pm-mcpd detached into its own session and waits for the round trip")
    void startsDetached() throws Exception {
        var client = RuntimeAccess.local(environment(), Path.of("/nonexistent"), Map.of(), true);
        var runtime = Thread.ofVirtual().start(() -> {
            try {
                var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!Files.exists(marker) && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                fixture.start((request, response) -> response.json(200, "{\"version\":\"t\"}"));
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var response = client.send(HttpRequest.get("/api/v1/status"));
        runtime.join();

        assertEquals(200, response.status());
        var reported = Files.readString(marker).strip().split(" ");
        daemonPid = Long.parseLong(reported[0]);
        assertEquals(reported[0], reported[1], "pm-mcpd leads its own process group (setsid)");
        assertTrue(ProcessHandle.of(daemonPid).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(Files.readString(fixture.paths().daemonLog()).contains("daemon started"));
    }

    @Test
    @DisplayName("reports a missing pm-mcpd as runtime unavailable")
    void missingDaemon() {
        var launcher = new PosixDaemonLauncher(PosixPlatform.current(), Map.of());
        var absent = home.resolve("bin/absent");
        var log = fixture.base().resolveSibling("log");

        assertThrows(RuntimeUnavailableException.class, () -> launcher.launch(absent, log));
    }

    @Test
    @DisplayName("learns the exit code of a started process that ended")
    void exitCode() throws Exception {
        var script = home.resolve("bin/exit77");
        Files.writeString(script, "#!/bin/sh\nexit 77\n");
        script.toFile().setExecutable(true);
        var launched = new PosixDaemonLauncher(PosixPlatform.current(), Map.of())
                .launch(script, fixture.base().resolveSibling("log"));

        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (launched.exitCode().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertEquals(77, launched.exitCode().orElseThrow());
        assertTrue(launched.pid() > 0);
        assertFalse(ProcessHandle.of(launched.pid()).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    @DisplayName("a client without on-demand start never starts a runtime")
    void noStart() throws Exception {
        var client = RuntimeAccess.local(environment(), Path.of("/nonexistent"), Map.of(), false);

        assertThrows(RuntimeUnavailableException.class, () -> client.send(HttpRequest.get("/api/v1/status")));
        assertFalse(Files.exists(marker));
    }
}
