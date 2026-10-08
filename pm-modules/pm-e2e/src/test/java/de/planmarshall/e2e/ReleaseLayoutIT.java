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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The release layout end to end: {@code pm-operator runtime start} brings {@code pm-mcpd} up on demand,
 * {@code pm-operator status --json} reports it, a host round trip through {@code pm-mcp serve --client neutral}
 * over real {@code stdio} (initialize, tools/list, tools/call {@code hello}) answers correctly, and
 * {@code pm-operator runtime stop} stops it. Writes {@code target/verification-results/e2e-release-layout-<mode>.json}.
 */
@DisplayName("Release layout: operator, relay and daemon")
class ReleaseLayoutIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Set<String> CORE_TOOLS = Set.of("pm_state", "pm_do", "pm_wait", "pm_plans", "pm_epics",
            "pm_build", "pm_lsp", "pm_skills", "pm_skill", "pm_skill_file");

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReleaseLayout.Mode.class)
    @DisplayName("starts on demand, reports status, relays a host session, and stops")
    void shouldRunReleaseLayout(ReleaseLayout.Mode mode, @TempDir Path workspace) throws Exception {
        try (var layout = ReleaseLayout.stage(mode)) {
            var values = new LinkedHashMap<String, Object>();
            values.put("mode", mode.label());
            values.put("home_from", mode == ReleaseLayout.Mode.JVM ? "PM_MCP_HOME" : "executable");
            values.put("transport_library_staged", layout.transportLibrary() != null);

            var before = layout.operator("status", "--json");
            assertEquals(0, before.exit(), before.stderr());
            assertFalse(ReleaseLayout.json(before).path("running").asBoolean(true), before.stdout());

            var start = layout.operator("runtime", "start");
            assertEquals(0, start.exit(), start.stderr() + layout.daemonLog());
            assertTrue(start.stdout().startsWith("runtime   running (pid "), start.stdout());
            values.put("runtime_start_ms", start.millis());

            var status = layout.operator("status", "--json");
            assertEquals(0, status.exit(), status.stderr());
            var json = ReleaseLayout.json(status);
            var pid = layout.runtimePid().orElseThrow();
            assertEquals(pid, json.path("pid").asLong(), status.stdout());
            assertEquals("unix", json.path("listener").asText(), status.stdout());
            assertEquals(layout.socket().toString(), json.path("socket_path").asText(), status.stdout());
            assertFalse(json.path("version").asText().isBlank(), status.stdout());
            assertFalse(json.path("web").path("enabled").asBoolean(true), status.stdout());
            values.put("status_ms", status.millis());
            values.put("daemon_pid", pid);

            try (var relay = StdioPeer.start(layout.command("pm-mcp", "serve", "--client", "neutral"),
                         layout.environment(), workspace)) {
                var t0 = System.nanoTime();
                relay.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                        + "\"2025-11-25\",\"clientInfo\":{\"name\":\"e2e\",\"version\":\"1\"},\"capabilities\":{}}}");
                var initialize = relay.response(1, WAIT);
                var t1 = System.nanoTime();
                relay.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                relay.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
                var list = relay.response(2, WAIT);
                var t2 = System.nanoTime();
                relay.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"hello\","
                        + "\"arguments\":{\"name\":\"Release\"}}}");
                var call = relay.response(3, WAIT);
                var t3 = System.nanoTime();

                assertEquals("plan-marshall-mcp", initialize.path("result").path("serverInfo").path("name").asText(),
                        initialize.toString());
                var tools = new TreeSet<String>();
                list.path("result").path("tools").forEach(tool -> tools.add(tool.path("name").asText()));
                assertTrue(tools.contains("hello"), tools.toString());
                assertTrue(tools.containsAll(CORE_TOOLS), tools.toString());
                assertEquals("Hello, Release!", call.path("result").path("content").path(0).path("text").asText(),
                        call.toString());
                assertEquals(0, relay.closeAndWait(WAIT), relay.stderr().toString());
                values.put("relay_initialize_ms", (t1 - t0) / 1_000_000);
                values.put("relay_tools_list_ms", (t2 - t1) / 1_000_000);
                values.put("relay_hello_call_ms", (t3 - t2) / 1_000_000);
                values.put("tools_listed", tools.size());
            }

            var stop = layout.operator("runtime", "stop");
            assertEquals(0, stop.exit(), stop.stderr());
            assertEquals("runtime   stopping", stop.stdout().strip());
            var stopStart = System.nanoTime();
            assertTrue(ReleaseLayout.awaitGone(pid, WAIT), "daemon " + pid + " still running");
            values.put("stop_to_exit_ms", (System.nanoTime() - stopStart) / 1_000_000 + stop.millis());
            var after = layout.operator("status", "--json");
            assertFalse(ReleaseLayout.json(after).path("running").asBoolean(true), after.stdout());
            values.put("socket_removed", !Files.exists(layout.socket()));
            VerificationResults.write("e2e-release-layout-" + mode.label(), values, true);
        }
    }
}
