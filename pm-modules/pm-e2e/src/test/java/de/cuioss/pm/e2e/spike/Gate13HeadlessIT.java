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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Gate 13 (doc/roadmap/technical_linux.adoc L10/L12, technical_macos.adoc M14/M15): one worker of the harness
 * completes one task through the real relay {@code pm-mcp serve --job}, confined on Linux. Recorded: the socket
 * connect from inside the confinement, the pm-exec launch report, own login (no credential variable needed), the
 * files the harness wrote (walk of the write set before and after), structured output, usage fields, the exit on
 * SIGTERM with orphaned relays, session residue, the MCP methods the harness used, and the largest task input
 * ({@code spike.sizes}) that completes. Figures: {@code target/verification-results/gate13-<harness>.json}.
 * <p>
 * Opt-in: {@code -Dspike.harness=claude|opencode|codex -Dspike.item=gate13 [-Dspike.sizes=8000,32000,128000]}.
 */
@EnabledIfSystemProperty(named = SpikeSettings.HARNESS, matches = SpikeSettings.HARNESSES)
@EnabledIfSystemProperty(named = SpikeSettings.ITEM, matches = "gate13")
@DisplayName("Gate 13: headless capabilities of a harness under target conditions")
class Gate13HeadlessIT {

    private static final Duration TASK_BOUND = Duration.ofMinutes(5);
    private static final String BASE_TASK = "t13-base";

    @Test
    @DisplayName("completes a task through the worker relay and records the headless capabilities")
    void shouldCompleteTaskThroughRelay() throws Exception {
        var settings = SpikeSettings.fromSystemProperties();
        var harness = settings.harness();
        var name = "gate13-" + harness.id();
        var runDir = SpikeResults.runDirectory(name);
        var stage = SpikeStage.stage(runDir.resolve("stage"), settings.layout());
        var tasks = new ArrayList<ScenarioBuilder.SizedTask>();
        tasks.add(new ScenarioBuilder.SizedTask(BASE_TASK, "h1", 0));
        for (var size : settings.sizes()) {
            tasks.add(new ScenarioBuilder.SizedTask("t13-s" + size, "h" + size, size));
        }
        var scenario = ScenarioBuilder.write(ScenarioBuilder.sized(settings.waitSeconds(), tasks),
                runDir.resolve("scenario.json"));
        var values = SpikeResults.environment(settings, stage);
        var pass = false;
        try (var daemon = SpikeDaemon.start(stage, SpikeResults.base(), scenario, runDir)) {
            var workspace = SpikeResults.workspace(name);
            var launcher = new WorkerLauncher(settings, stage, daemon, runDir, workspace);
            var tail = new EventTail(runDir.resolve("events.jsonl"));
            var credentials = harness.credentialEnv().stream().filter(variable -> System.getenv(variable) != null)
                    .toList();
            values.put("credential_env_present", credentials);
            values.put("own_login", credentials.isEmpty());
            var writeSet = launcher.writeSet(workspace.resolve("h1-g1"));
            values.put("write_set", writeSet.stream().map(Path::toString).toList());
            var observed = new ArrayList<>(writeSet);
            observed.addAll(harness.stateDirs(WorkerLauncher.home()));
            var roots = snapshotRoots(observed, workspace);
            var before = FileSnapshot.of(roots);

            var base = runOne(launcher, tail, "h1", BASE_TASK, 0);
            var after = FileSnapshot.of(roots);
            values.put("task", base);
            var written = before.changedIn(after).stream().filter(path -> !path.startsWith(daemon.base())).toList();
            values.put("files_written", limit(written));
            values.put("files_written_count", written.size());
            values.put("files_written_roots", roots(written, roots));
            values.put("files_removed_count", before.removedIn(after).size());
            values.put("snapshot_truncated", before.truncated() || after.truncated());
            var session = (String) ((Map<?, ?>) base.get("output")).get("session_id");
            values.put("session_residue", limit(written.stream()
                    .filter(path -> session != null && path.toString().contains(session)
                            || path.startsWith(workspace)).toList()));

            values.put("sigterm", sigterm(launcher, tail));

            var sizes = new LinkedHashMap<String, Object>();
            var largest = 0;
            for (var size : settings.sizes()) {
                var run = runOne(launcher, tail, "h" + size, "t13-s" + size, size);
                sizes.put(Integer.toString(size), run);
                if (Boolean.TRUE.equals(run.get("completed"))) {
                    largest = Math.max(largest, size);
                }
            }
            values.put("sizes", sizes);
            values.put("largest_input_completed_chars", largest);

            var output = (Map<?, ?>) base.get("output");
            var sigterm = (Map<?, ?>) values.get("sigterm");
            var criteria = new LinkedHashMap<String, Boolean>();
            criteria.put("socket_connect", Boolean.TRUE.equals(base.get("connected")));
            criteria.put("task_completed", Boolean.TRUE.equals(base.get("completed")));
            criteria.put("structured_output", Boolean.TRUE.equals(output.get("structured_output_parses"))
                    && ((Number) output.get("non_json_lines")).intValue() == 0);
            criteria.put("usage_fields", !((Map<?, ?>) output.get("usage")).isEmpty());
            criteria.put("sigterm_exit", Boolean.TRUE.equals(sigterm.get("exited_on_sigterm"))
                    && ((List<?>) sigterm.get("orphaned_relays")).isEmpty());
            values.put("criteria", criteria);
            pass = criteria.values().stream().allMatch(Boolean::booleanValue);
            values.put("daemon_log_tail", daemon.tail());
        } finally {
            values.put("run_dir", runDir.toString());
            var file = SpikeResults.write(name, values, pass);
            values.put("result_file", file.toString());
        }

        assertTrue(pass, "gate 13 failed for " + harness.id() + ": " + values.get("criteria") + ", see " + runDir);
    }

    private static Map<String, Object> runOne(WorkerLauncher launcher, EventTail tail, String worker, String taskId,
            int size) throws Exception {
        var started = System.currentTimeMillis();
        var run = launcher.launch(worker, 1, "single", Prompts.SINGLE_TASK, WorkerLauncher.PROTOCOL_TOOLS,
                Prompts.SINGLE_TASK_SCHEMA, null);
        var exited = run.awaitExit(TASK_BOUND);
        if (!exited) {
            run.kill();
            run.awaitExit(Duration.ofSeconds(10));
        }
        run.closeLog();
        tail.poll();
        var events = tail.all();
        var mine = events.stream().filter(event -> worker.equals(event.worker()) && event.tMs() >= started).toList();
        var methods = new TreeSet<String>();
        events.stream().filter(event -> "rx".equals(event.name()) && event.tMs() >= started
                && event.tMs() <= Math.max(run.exitMs(), started)).forEach(event -> methods.add(event.text("method")));
        var submit = mine.stream().filter(event -> "submit".equals(event.name()) && taskId.equals(event.taskId()))
                .findFirst();
        var result = new LinkedHashMap<String, Object>();
        result.put("worker", worker);
        result.put("task_id", taskId);
        result.put("input_chars", size);
        result.put("connected", mine.stream().anyMatch(event -> "wait_start".equals(event.name())));
        result.put("completed", submit.isPresent());
        result.put("decision", submit.map(event -> event.text("decision")).orElse(null));
        result.put("duration_ms", (run.exitMs() > 0 ? run.exitMs() : System.currentTimeMillis()) - started);
        result.put("exit_code", run.exitCode());
        result.put("timed_out", !exited);
        result.put("launch_report", run.launchReport());
        result.put("mcp_methods", List.copyOf(methods));
        result.put("stub_events", mine.stream().map(StubEvent::name).distinct().toList());
        result.put("output", HarnessOutput.parse(launcher.harness(), run.stdout()).toMap());
        result.put("stderr_tail", tail(run.stderr()));
        return result;
    }

    private static Map<String, Object> sigterm(WorkerLauncher launcher, EventTail tail) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        var started = System.currentTimeMillis();
        var run = launcher.launch("hterm", 1, "single", Prompts.SINGLE_TASK, WorkerLauncher.PROTOCOL_TOOLS,
                Prompts.SINGLE_TASK_SCHEMA, null);
        var held = await(tail, event -> "wait_start".equals(event.name()) && "hterm".equals(event.worker())
                && event.tMs() >= started, Duration.ofMinutes(2));
        result.put("held_wait_before_sigterm", held);
        sleep(2_000);
        var relays = ProcessTree.relays(run.pid());
        result.put("relays_before", relays);
        var exitMs = run.terminate(Duration.ofSeconds(10));
        result.put("exit_after_sigterm_ms", exitMs);
        result.put("exited_on_sigterm", exitMs >= 0);
        result.put("exit_code", run.exitCode());
        sleep(2_000);
        result.put("orphaned_relays", List.copyOf(ProcessTree.alive(relays).values()));
        ProcessTree.alive(relays).keySet().forEach(pid -> ProcessTree.signal(pid, "KILL"));
        run.closeLog();
        return result;
    }

    private static boolean await(EventTail tail, Predicate<StubEvent> condition, Duration bound) {
        var deadline = System.currentTimeMillis() + bound.toMillis();
        while (System.currentTimeMillis() < deadline) {
            tail.poll();
            if (tail.all().stream().anyMatch(condition)) {
                return true;
            }
            sleep(200);
        }
        return false;
    }

    private static List<Path> snapshotRoots(List<Path> writeSet, Path workspace) {
        var roots = new ArrayList<Path>();
        for (var path : writeSet) {
            if (!"/dev".equals(path.toString())) {
                roots.add(real(path.equals(workspace.resolve("h1-g1")) ? workspace : path));
            }
        }
        if (!WorkerLauncher.confined()) {
            roots.add(real(Path.of(System.getProperty("java.io.tmpdir"))));
        }
        return roots.stream().distinct().toList();
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException _) {
            return path;
        }
    }

    private static Map<String, Integer> roots(List<Path> written, List<Path> roots) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var path : written) {
            var root = roots.stream().filter(path::startsWith).findFirst().map(Path::toString).orElse("other");
            counts.merge(root, 1, Integer::sum);
        }
        return counts;
    }

    private static List<String> limit(List<Path> paths) {
        return paths.stream().limit(300).map(Path::toString).toList();
    }

    private static List<String> tail(List<String> lines) {
        return lines.subList(Math.max(0, lines.size() - 10), lines.size());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
