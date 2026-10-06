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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Gate 11 (doc/roadmap/technical_macos.adoc M9): the ten core tools with their final flat schemas register and are
 * each called once successfully on a host, through the session relay {@code pm-mcp serve --client <id>} in front
 * of the staged daemon (product tool set, no stub scenario). The host runs headless and is asked to call each tool
 * once with minimal valid arguments. Verified from the daemon side (its MCP traffic record): the tools the daemon
 * listed to the host and every {@code tools/call} with its response. Recorded per tool: listed (by the daemon, and
 * by the host where its output names its tools), called, succeeded, and the host's error text when it refused a
 * tool. Figures: {@code target/verification-results/gate11-<host>.json}.
 * <p>
 * Opt-in: {@code -Dspike.harness=claude|opencode|antigravity -Dspike.item=gate11 [-Dspike.model=…]
 * [-Dspike.layout=native|jvm|auto]}.
 */
@EnabledIfSystemProperty(named = SpikeSettings.HARNESS, matches = HostSession.HOSTS)
@EnabledIfSystemProperty(named = SpikeSettings.ITEM, matches = "gate11")
@DisplayName("Gate 11: flat tool schemas register and are callable on a host")
class Gate11FlatSchemaIT {

    /** The core tools with minimal valid arguments (pm-mcp-server/src/main/resources/pm-mcp/tools/*.json). */
    static final Map<String, String> CALLS = calls();

    private static final Duration SESSION_BOUND = Duration.ofMinutes(6);

    private static Map<String, String> calls() {
        var calls = new LinkedHashMap<String, String>();
        calls.put("pm_state", "{}");
        calls.put("pm_do", "{\"link\":\"next\"}");
        calls.put("pm_wait", "{}");
        calls.put("pm_plans", "{}");
        calls.put("pm_epics", "{}");
        calls.put("pm_build", "{\"plan_id\":\"NO_PLAN\",\"command\":\"compile\"}");
        calls.put("pm_lsp", "{\"plan_id\":\"NO_PLAN\",\"op\":\"diagnostics\"}");
        calls.put("pm_skills", "{}");
        calls.put("pm_skill", "{\"uri\":\"skill://pm/demo/SKILL.md\"}");
        calls.put("pm_skill_file", "{\"uri\":\"skill://pm/demo/notes.md\"}");
        return calls;
    }

    static String prompt(HostSession host, List<String> tools) {
        var text = new StringBuilder("This is a connectivity test of the MCP server \"").append(HostSession.SERVER)
                .append("\". Call each of the following ").append(tools.size())
                .append(" tools exactly once, in this order, with exactly the ")
                .append("arguments shown. Do not call any other tool, do not retry a call, and ignore what the ")
                .append("tools answer. When all ").append(tools.size())
                .append(" calls are done, reply with the single word DONE.\n");
        var index = 1;
        for (var tool : tools) {
            text.append(index++).append(". ").append(host.hostToolName(tool)).append(' ').append(CALLS.get(tool))
                    .append('\n');
        }
        return text.toString();
    }

    /** @return the tools of {@code spike.tools} (a comma-separated subset, for a repeat), else all ten */
    static List<String> selectedTools() {
        var selected = System.getProperty("spike.tools", "");
        if (selected.isBlank()) {
            return List.copyOf(CALLS.keySet());
        }
        return Arrays.stream(selected.split(",")).map(String::strip).filter(CALLS::containsKey).toList();
    }

    @Test
    @DisplayName("lists and calls each of the ten core tools once through pm-mcp serve --client")
    void shouldListAndCallCoreTools() throws Exception {
        var host = HostSession.fromSystemProperties();
        var listen = System.getProperty(SpikeDaemon.LISTEN_PROPERTY, "");
        var name = "gate11-" + host.clientId() + (listen.isBlank() ? "" : "-listen-" + listen.strip())
                + (System.getProperty("spike.tools", "").isBlank() ? "" : "-subset");
        var runDir = SpikeResults.runDirectory(name);
        var stage = SpikeStage.stage(runDir.resolve("stage"), System.getProperty("spike.layout", "auto"));
        var values = new LinkedHashMap<String, Object>();
        values.put("host", host.clientId());
        values.put("host_version", host.version());
        values.put("model", host.model());
        values.put("layout", stage.mode());
        values.put("listen_answer", listen.isBlank() ? "quarkus-mcp-server" : listen.strip());
        values.put("run_dir", runDir.toString());
        var pass = false;
        try (var daemon = SpikeDaemon.start(stage, SpikeResults.base(), null, runDir)) {
            var session = SpikeResults.workspace(name);
            var tools = selectedTools();
            values.put("tools_asked", tools);
            var started = System.currentTimeMillis();
            var run = host.start(stage, daemon.base(), session, prompt(host, tools), tools,
                    runDir.resolve("harness-" + host.clientId() + ".jsonl"));
            try {
                var exited = run.awaitExit(SESSION_BOUND);
                if (!exited) {
                    run.terminate(Duration.ofSeconds(10));
                }
                run.closeLog();
                values.put("session_ms", System.currentTimeMillis() - started);
                values.put("timed_out", !exited);
                values.put("exit_code", run.exitCode());
            } finally {
                host.restore();
            }
            var traffic = McpTraffic.read(runDir.resolve(SpikeDaemon.TRAFFIC_FILE));
            var hostListed = HostOutput.listedTools(host, run.stdout());
            var listed = traffic.toolsListed();
            var calls = traffic.calls();
            var perTool = new LinkedHashMap<String, Object>();
            var allCalled = true;
            for (var tool : tools) {
                var mine = calls.stream().filter(call -> tool.equals(call.tool())).toList();
                var ok = mine.stream().anyMatch(McpTraffic.Call::succeeded);
                allCalled &= ok;
                var entry = new LinkedHashMap<String, Object>();
                entry.put("listed_by_daemon", listed.contains(tool));
                entry.put("listed_by_host", hostListed == null ? null : hostListed.contains(host.hostToolName(tool)));
                entry.put("calls", mine.size());
                entry.put("succeeded", ok);
                entry.put("errors", mine.stream().map(McpTraffic.Call::errorText).filter(text -> text != null)
                        .toList());
                entry.put("refused", listed.contains(tool) && hostListed != null
                        && !hostListed.contains(host.hostToolName(tool)));
                entry.put("host_error_text", HostOutput.mentions(run, host.hostToolName(tool)));
                perTool.put(tool, entry);
            }
            values.put("tools", perTool);
            values.put("tools_listed_by_daemon", List.copyOf(listed));
            values.put("tools_listed_by_host", hostListed);
            values.put("mcp_methods", traffic.methodsReceived());
            values.put("subscriptions_listen", traffic.requests("subscriptions/listen").stream()
                    .map(request -> Map.of("request", request.body().toString(), "daemon_sent",
                            traffic.sentAfter(request).stream().limit(3).map(sent -> sent.body().toString())
                                    .toList()))
                    .toList());
            values.put("other_calls", calls.stream().map(McpTraffic.Call::tool)
                    .filter(tool -> !CALLS.containsKey(tool)).toList());
            values.put("schema_errors", HostOutput.schemaErrors(run));
            values.put("final_answer", HostOutput.finalText(host, run.stdout()));
            values.put("stderr_tail", tail(run.stderr()));
            values.put("host_log", host.diagnostics(started));
            values.put("daemon_alive", daemon.alive());
            pass = allCalled;
        } finally {
            SpikeResults.write(name, values, pass);
        }
        assertTrue(pass, "gate 11 failed for " + host.clientId() + ": " + values.get("tools") + ", see " + runDir);
    }

    private static List<String> tail(List<String> lines) {
        return new ArrayList<>(lines.subList(Math.max(0, lines.size() - 15), lines.size()));
    }

    /** What the host itself says in its output. */
    static final class HostOutput {

        private HostOutput() {
        }

        /**
         * @param host  the host
         * @param lines its standard output
         * @return the tool names the host reports as available, {@code null} if its output does not say
         */
        static List<String> listedTools(HostSession host, List<String> lines) {
            if (host != HostSession.CLAUDE) {
                return null;
            }
            for (var line : lines) {
                try {
                    var node = StubEvent.JSON.readTree(line);
                    if ("system".equals(node.path("type").asText()) && "init".equals(node.path("subtype").asText())) {
                        var tools = new ArrayList<String>();
                        node.path("tools").forEach(tool -> tools.add(tool.asText()));
                        return tools;
                    }
                } catch (JsonProcessingException _) {
                    // not a JSON line
                }
            }
            return null;
        }

        /**
         * @param host  the host
         * @param lines its standard output
         * @return the final answer of the host, where its output carries one
         */
        static String finalText(HostSession host, List<String> lines) {
            for (var i = lines.size() - 1; i >= 0; i--) {
                try {
                    var node = StubEvent.JSON.readTree(lines.get(i));
                    if (node.has("result") && node.path("result").isTextual()) {
                        return node.path("result").asText();
                    }
                    if (node.path("result").path("response").isTextual()) {
                        return node.path("result").path("response").asText().strip();
                    }
                    var text = node.path("part").path("text");
                    if (text.isTextual()) {
                        return text.asText();
                    }
                } catch (JsonProcessingException _) {
                    if (host == HostSession.ANTIGRAVITY && !lines.get(i).isBlank()) {
                        return lines.get(i);
                    }
                }
            }
            return null;
        }

        /**
         * @param run   the host process
         * @param token a host tool name
         * @return output lines that mention the tool together with an error, at most five
         */
        static List<String> mentions(Worker run, String token) {
            var found = new ArrayList<String>();
            for (var line : allLines(run)) {
                var lower = line.replace("\"is_error\":false", "").toLowerCase(Locale.ROOT);
                if (line.contains(token) && !line.contains("\"subtype\":\"init\"") && (lower.contains("error") || lower.contains("invalid")
                        || lower.contains("schema") || lower.contains("refus"))) {
                    found.add(shorten(line));
                }
            }
            return found.subList(0, Math.min(5, found.size()));
        }

        /**
         * @param run the host process
         * @return output lines that mention a schema problem, at most ten
         */
        static List<String> schemaErrors(Worker run) {
            var found = new ArrayList<String>();
            for (var line : allLines(run)) {
                var lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("schema") && (lower.contains("invalid") || lower.contains("error")
                        || lower.contains("unsupported") || lower.contains("reject"))) {
                    found.add(shorten(line));
                }
            }
            return found.subList(0, Math.min(10, found.size()));
        }

        private static List<String> allLines(Worker run) {
            var lines = new ArrayList<>(run.stdout());
            lines.addAll(run.stderr());
            return lines;
        }

        private static String shorten(String line) {
            return line.length() > 600 ? line.substring(0, 600) + "…" : line;
        }
    }
}
