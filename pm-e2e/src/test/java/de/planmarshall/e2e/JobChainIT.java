/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The job chain as far as it exists before the job runtime: {@code pm-exec} launches the worker relay
 * {@code pm-mcp serve --job --socket <socket>} with a job token, and the relay connects to the staged daemon from
 * inside the launcher (on Linux inside its Landlock domain, {@code --deny-read <PM_MCP_BASE>}). No job exists
 * before the job runtime, so the daemon refuses the token and the relay ends with its diagnostic: the refusal is
 * the daemon's answer and proves the connect. Writes
 * {@code target/verification-results/gate13-job-chain-socket-connect-<mode>.json}.
 */
@DisplayName("Job chain: pm-exec, worker relay and daemon")
class JobChainIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final boolean LINUX = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReleaseLayout.Mode.class)
    @DisplayName("reaches the daemon from inside pm-exec, which refuses the job token of no job")
    void shouldReachDaemonFromInsidePmExec(ReleaseLayout.Mode mode, @TempDir Path scratch) throws Exception {
        try (var layout = ReleaseLayout.stage(mode)) {
            var start = layout.operator("runtime", "start");
            assertEquals(0, start.exit(), start.stderr() + layout.daemonLog());
            var command = new ArrayList<String>();
            command.add(layout.binary("pm-exec").toString());
            if (LINUX) {
                command.addAll(List.of("--deny-read", layout.base().toString(), "--read",
                        layout.socket().toString(), "--write", scratch.toString()));
            }
            command.add("--");
            command.addAll(layout.command("pm-mcp", "serve", "--job", "--socket", layout.socket().toString()));
            var environment = layout.environment();
            environment.remove("PM_MCP_HOME");
            environment.put("PM_MCP_JOB_TOKEN", ReleaseLayout.JOB_TOKEN);
            environment.put("PM_MCP_JOB_ID", ReleaseLayout.JOB_ID);
            var values = new LinkedHashMap<String, Object>();
            values.put("mode", mode.label());
            values.put("confinement_requested", LINUX ? "landlock --deny-read <PM_MCP_BASE>" : "none (macOS)");

            try (var worker = StdioPeer.start(command, environment, scratch)) {
                var t0 = System.nanoTime();
                worker.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                        + "\"2025-11-25\",\"clientInfo\":{\"name\":\"e2e-worker\",\"version\":\"1\"},"
                        + "\"capabilities\":{}}}");
                var ended = worker.process().waitFor(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                var t1 = System.nanoTime();
                assertTrue(ended, worker.stderr().toString());
                var exit = worker.closeAndWait(WAIT);

                var refused = refusalReported(worker);
                var report = launchReport(worker.stderr());
                values.put("launch_report", report == null ? null : report.toString());
                values.put("confinement", report == null ? null : report.path("confinement").asText());
                values.put("landlock_abi", report == null ? null : report.path("landlock_abi").asText());
                values.put("same_pid_after_execve", report != null
                        && report.path("pid").asLong() == worker.process().pid());
                values.put("socket_connect", refused);
                values.put("refused_ms", (t1 - t0) / 1_000_000);
                values.put("relay_exit", exit);
                VerificationResults.write("gate13-job-chain-socket-connect-" + mode.label(), values, refused);

                assertTrue(report != null && "launched".equals(report.path("pm_exec").asText()),
                        worker.stderr().toString());
                assertTrue(refused, worker.stderr() + layout.daemonLog());
                assertEquals(1, exit, worker.stderr().toString());
                assertFalse(worker.stderr().toString().contains(ReleaseLayout.JOB_TOKEN));
            }
        }
    }

    /** The relay's diagnostic arrives on a reader thread, so it may follow the exit by a moment. */
    private static boolean refusalReported(StdioPeer worker) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (worker.stderr().stream().anyMatch(line -> line.contains("refused the job token"))) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static JsonNode launchReport(List<String> stderr) {
        var json = new ObjectMapper();
        for (var line : stderr) {
            if (line.startsWith("{\"pm_exec\"")) {
                try {
                    return json.readTree(line);
                } catch (JsonProcessingException _) {
                    return null;
                }
            }
        }
        return null;
    }
}
