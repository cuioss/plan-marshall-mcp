/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The job chain as far as it exists before the job runtime: {@code pm-exec} launches the worker relay
 * {@code pm-mcp serve --job --socket <socket>} with the spike job token, the relay connects to the staged daemon
 * from inside the launcher (on Linux inside its Landlock domain, {@code --deny-read <PM_MCP_BASE>}), and initialize
 * and tools/list answer over its {@code stdio}. Writes
 * {@code target/verification-results/gate13-job-chain-socket-connect-<mode>.json}.
 */
@DisplayName("Job chain: pm-exec, worker relay and daemon")
class JobChainIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final boolean LINUX = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReleaseLayout.Mode.class)
    @DisplayName("relays initialize and tools/list from inside pm-exec to the daemon")
    void shouldRelayFromInsidePmExec(ReleaseLayout.Mode mode, @TempDir Path scratch) throws Exception {
        try (var layout = ReleaseLayout.stage(mode)) {
            var start = layout.operator("runtime", "start");
            assertEquals(0, start.exit(), start.stderr() + layout.daemonLog());
            var command = new ArrayList<String>();
            command.add(layout.binary("pm-exec").toString());
            if (LINUX) {
                command.addAll(List.of("--deny-read", layout.base().toString(), "--write",
                        scratch.toString()));
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
                var initialize = worker.response(1, WAIT);
                var t1 = System.nanoTime();
                worker.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                worker.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
                var list = worker.response(2, WAIT);
                var t2 = System.nanoTime();
                var exit = worker.closeAndWait(WAIT);

                var report = launchReport(worker.stderr());
                values.put("launch_report", report == null ? null : report.toString());
                values.put("confinement", report == null ? null : report.path("confinement").asText());
                values.put("landlock_abi", report == null ? null : report.path("landlock_abi").asText());
                values.put("same_pid_after_execve", report != null
                        && report.path("pid").asLong() == worker.process().pid());
                var tools = new TreeSet<String>();
                list.path("result").path("tools").forEach(tool -> tools.add(tool.path("name").asText()));
                var connected = initialize.has("result") && list.has("result");
                values.put("socket_connect", connected);
                values.put("initialize_ms", (t1 - t0) / 1_000_000);
                values.put("tools_list_ms", (t2 - t1) / 1_000_000);
                values.put("tools_listed", tools.size());
                values.put("relay_exit", exit);
                VerificationResults.write("gate13-job-chain-socket-connect-" + mode.label(), values, connected);

                assertTrue(report != null && "launched".equals(report.path("pm_exec").asText()),
                        worker.stderr().toString());
                assertEquals("plan-marshall-mcp", initialize.path("result").path("serverInfo").path("name").asText(),
                        initialize + " " + worker.stderr());
                assertTrue(tools.contains("hello"), list.toString());
                assertFalse(tools.isEmpty());
                assertEquals(0, exit, worker.stderr().toString());
            }
        }
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
