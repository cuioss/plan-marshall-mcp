/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Gate 9 (doc/roadmap/technical_macos.adoc M13): the host starts the session relay {@code pm-mcp serve --client
 * <id>} from its MCP configuration; ending the host session ends the relay, and the daemon survives. Two session
 * ends per host, both headless: the host finishes its turn and exits ({@code exit}), and the host is sent SIGTERM
 * while its relay is connected ({@code sigterm}). The relay processes are taken from the process table (the staged
 * relay path with {@code serve --client}); the daemon's survival from its process and {@code GET /api/v1/status}.
 * Codex's MCP idle timeout belongs to {@link Gate9IdleTimeoutIT}. Figures:
 * {@code target/verification-results/gate9-relay-<host>.json}.
 * <p>
 * Opt-in: {@code -Dspike.harness=claude|opencode|antigravity -Dspike.item=gate9-relay [-Dspike.model=…]}.
 */
@EnabledIfSystemProperty(named = SpikeSettings.HARNESS, matches = HostSession.HOSTS)
@EnabledIfSystemProperty(named = SpikeSettings.ITEM, matches = "gate9-relay")
@DisplayName("Gate 9: relay start and termination with the host session")
class Gate9RelayLifecycleIT {

    private static final Duration SESSION_BOUND = Duration.ofMinutes(4);
    private static final Duration RELAY_BOUND = Duration.ofMinutes(2);
    private static final long RELAY_EXIT_WAIT_MS = 5_000;
    private static final long POLL_MS = 100;

    @Test
    @DisplayName("ends the relay with the host session, on exit and on SIGTERM, while the daemon survives")
    void shouldEndRelayWithSession() throws Exception {
        var host = HostSession.fromSystemProperties();
        var name = "gate9-relay-" + host.clientId();
        var runDir = SpikeResults.runDirectory(name);
        var stage = SpikeStage.stage(runDir.resolve("stage"), System.getProperty("spike.layout", "auto"));
        var values = new LinkedHashMap<String, Object>();
        values.put("host", host.clientId());
        values.put("host_version", host.version());
        values.put("model", host.model());
        values.put("layout", stage.mode());
        values.put("run_dir", runDir.toString());
        values.put("codex_idle_timeout", "not run (operator decision)");
        var pass = false;
        try (var daemon = SpikeDaemon.start(stage, SpikeResults.base(), null, runDir)) {
            var exit = session(host, stage, daemon, runDir, "exit", false);
            values.put("exit", exit);
            var sigterm = session(host, stage, daemon, runDir, "sigterm", true);
            values.put("sigterm", sigterm);
            values.put("mcp_methods", McpTraffic.read(runDir.resolve(SpikeDaemon.TRAFFIC_FILE)).methodsReceived());
            pass = passed(exit) && passed(sigterm);
        } finally {
            SpikeResults.write(name, values, pass);
        }
        assertTrue(pass, "gate 9 relay lifecycle failed for " + host.clientId() + ": " + values + ", see " + runDir);
    }

    private static boolean passed(Map<String, Object> session) {
        return Boolean.TRUE.equals(session.get("relay_started")) && Boolean.TRUE.equals(session.get("relay_ended"))
                && Boolean.TRUE.equals(session.get("daemon_survived"));
    }

    private static Map<String, Object> session(HostSession host, SpikeStage stage, SpikeDaemon daemon, Path runDir,
            String kind, boolean terminate) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        var prompt = terminate
                ? "Call the tool " + host.hostToolName("pm_state") + " with {} and then write a 300-word essay about "
                + "lighthouses, slowly, paragraph by paragraph."
                : "Call the tool " + host.hostToolName("pm_state") + " with {} once, then reply with the single word "
                + "DONE.";
        var started = System.currentTimeMillis();
        var run = host.start(stage, daemon.base(), SpikeResults.workspace("gate9-relay-" + kind), prompt,
                List.of("pm_state"), runDir.resolve("harness-" + host.clientId() + "-" + kind + ".jsonl"));
        var relays = new LinkedHashSet<Long>();
        var descendants = new LinkedHashSet<Long>();
        Long relaySeenMs = null;
        try {
            var deadline = System.currentTimeMillis() + (terminate ? RELAY_BOUND : SESSION_BOUND).toMillis();
            while (run.alive() && System.currentTimeMillis() < deadline) {
                var table = ProcessTree.table();
                var mine = ProcessTree.descendants(table, run.pid()).stream().map(ProcessTree.Entry::pid).toList();
                for (var entry : table) {
                    if (isRelay(entry, stage, host)) {
                        relays.add(entry.pid());
                        if (mine.contains(entry.pid())) {
                            descendants.add(entry.pid());
                        }
                        if (relaySeenMs == null) {
                            relaySeenMs = System.currentTimeMillis() - started;
                        }
                    }
                }
                if (terminate && relaySeenMs != null) {
                    Thread.sleep(3_000);
                    break;
                }
                Thread.sleep(POLL_MS);
            }
            result.put("relay_started", !relays.isEmpty());
            result.put("relay_seen_ms", relaySeenMs);
            result.put("relay_pids", List.copyOf(relays));
            result.put("relay_descendant_of_host", !descendants.isEmpty() && descendants.containsAll(relays));
            if (terminate) {
                var exitMs = run.terminate(Duration.ofSeconds(10));
                result.put("host_exit_after_sigterm_ms", exitMs);
            } else if (!run.awaitExit(SESSION_BOUND)) {
                result.put("host_timed_out", true);
                run.terminate(Duration.ofSeconds(10));
            }
            var hostEnd = System.currentTimeMillis();
            result.put("host_exit_code", run.exitCode());
            Long relayEndMs = null;
            var relayList = List.copyOf(relays);
            while (System.currentTimeMillis() - hostEnd < RELAY_EXIT_WAIT_MS) {
                if (ProcessTree.alive(relayList).isEmpty()) {
                    relayEndMs = System.currentTimeMillis() - hostEnd;
                    break;
                }
                Thread.sleep(POLL_MS);
            }
            var orphans = ProcessTree.alive(relayList);
            result.put("relay_ended", !relays.isEmpty() && orphans.isEmpty());
            result.put("relay_end_after_host_ms", relayEndMs);
            result.put("orphaned_relays", new ArrayList<>(orphans.values()));
            orphans.keySet().forEach(pid -> ProcessTree.signal(pid, "KILL"));
            var status = daemon.status();
            result.put("daemon_alive", daemon.alive());
            result.put("daemon_status", status);
            result.put("daemon_survived", daemon.alive() && status == 200);
            result.put("final_answer", Gate11FlatSchemaIT.HostOutput.finalText(host, run.stdout()));
            result.put("stderr_tail", run.stderr().stream().skip(Math.max(0, run.stderr().size() - 10)).toList());
        } finally {
            if (run.alive()) {
                run.terminate(Duration.ofSeconds(10));
            }
            run.closeLog();
            host.restore();
        }
        return result;
    }

    private static boolean isRelay(ProcessTree.Entry entry, SpikeStage stage, HostSession host) {
        var command = entry.command();
        return command.contains(stage.relay() + " serve --client " + host.clientId())
                || command.contains("PmMcp serve --client " + host.clientId());
    }
}
