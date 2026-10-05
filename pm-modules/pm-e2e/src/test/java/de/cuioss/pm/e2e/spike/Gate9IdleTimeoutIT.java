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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Gate 9 (doc/roadmap/technical_linux.adoc L12, technical_macos.adoc M13): the MCP call idle timeout of a harness,
 * measured with a wait call the stub holds far beyond any timeout, once without and once with progress
 * notifications every {@code spike.progress-s} seconds, through the real relay and with the harness's default
 * timeout settings. The end of the call is taken from the stub (cancellation or abort of the held call) and from
 * the harness's own output (the arrival of the tool result), whichever comes first; a call still held at
 * {@code spike.idle-max-s} counts as not timed out. Figures: {@code target/verification-results/gate9-<harness>.json}.
 * <p>
 * Opt-in: {@code -Dspike.harness=claude|opencode|codex -Dspike.item=gate9 [-Dspike.idle-max-s=…]
 * [-Dspike.progress-s=5]}. Claude Code's timeout was 1800 s in Part A, so its run takes over an hour.
 */
@EnabledIfSystemProperty(named = SpikeSettings.HARNESS, matches = SpikeSettings.HARNESSES)
@EnabledIfSystemProperty(named = SpikeSettings.ITEM, matches = "gate9")
@DisplayName("Gate 9: MCP call idle timeout and progress reset")
class Gate9IdleTimeoutIT {

    private static final String WORKER = "i1";
    private static final long POLL_MS = 200;

    @Test
    @DisplayName("measures the idle timeout with and without progress to +-10 s")
    void shouldMeasureIdleTimeout() throws Exception {
        var settings = SpikeSettings.fromSystemProperties();
        var name = "gate9-" + settings.harness().id();
        var stage = SpikeStage.stage(SpikeResults.runDirectory(name + "-stage"), settings.layout());
        var values = SpikeResults.environment(settings, stage);
        values.put("bound_s", settings.idleMaxSeconds());
        var silent = measure(settings, stage, name + "-noprog", 0);
        values.put("without_progress", silent);
        SpikeResults.write(name, values, false);
        var progress = measure(settings, stage, name + "-prog", settings.progressSeconds());
        values.put("with_progress", progress);
        values.put("progress_s", settings.progressSeconds());

        var silentEnd = (Long) silent.get("call_end_ms");
        var progressEnd = (Long) progress.get("call_end_ms");
        var measured = silentEnd != null || Boolean.TRUE.equals(silent.get("bound_reached"));
        values.put("idle_timeout_s", silentEnd == null ? null : silentEnd / 1000.0);
        Boolean resets = null;
        if (silentEnd != null) {
            resets = progressEnd == null ? Boolean.valueOf(Boolean.TRUE.equals(progress.get("bound_reached")))
                    : Boolean.valueOf(progressEnd > silentEnd + 30_000);
        }
        values.put("progress_resets_timeout", resets);
        values.put("resolution_ms", POLL_MS);
        var file = SpikeResults.write(name, values, measured);

        assertTrue(measured, "no idle timeout measured for " + settings.harness().id() + ", see " + file);
    }

    private static Map<String, Object> measure(SpikeSettings settings, SpikeStage stage, String name,
            int progressSeconds) throws Exception {
        var runDir = SpikeResults.runDirectory(name);
        var hold = settings.idleMaxSeconds() + 600;
        var scenario = ScenarioBuilder.write(ScenarioBuilder.blockingWait(hold, progressSeconds),
                runDir.resolve("scenario.json"));
        var result = new LinkedHashMap<String, Object>();
        result.put("run_dir", runDir.toString());
        try (var daemon = SpikeDaemon.start(stage, SpikeResults.base(), scenario, runDir)) {
            var launcher = new WorkerLauncher(settings, stage, daemon, runDir, SpikeResults.workspace(name));
            var tail = new EventTail(runDir.resolve("events.jsonl"));
            var run = launcher.launch(WORKER, 1, "single_wait", Prompts.SINGLE_WAIT, List.of("pull_wait"), null, null);
            Long start = null;
            Long serverEnd = null;
            String outcome = null;
            Long harnessEnd = null;
            var progressFrames = 0;
            var startDeadline = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
            while (true) {
                for (var event : tail.poll()) {
                    if (!WORKER.equals(event.worker())) {
                        continue;
                    }
                    if (start == null && "wait_start".equals(event.name())) {
                        start = event.tMs();
                    } else if (start != null && "wait_end".equals(event.name()) && serverEnd == null) {
                        serverEnd = event.tMs();
                        outcome = event.text("outcome");
                    } else if ("progress".equals(event.name())) {
                        progressFrames++;
                    }
                }
                if (start != null && harnessEnd == null) {
                    var begun = start;
                    harnessEnd = run.stdoutLines().stream()
                            .filter(line -> line.tMs() > begun && launcher.harness().reportsToolResult(line.text()))
                            .map(Worker.Line::tMs).findFirst().orElse(null);
                }
                var now = System.currentTimeMillis();
                if (start == null && (now > startDeadline || !run.alive())) {
                    break;
                }
                if (start != null && (serverEnd != null || harnessEnd != null || !run.alive()
                        || now - start > settings.idleMaxSeconds() * 1000L)) {
                    break;
                }
                Thread.sleep(POLL_MS);
            }
            var exit = run.alive() ? null : Long.valueOf(run.exitMs());
            run.terminate(Duration.ofSeconds(10));
            run.closeLog();
            result.put("call_started", start != null);
            result.put("progress_s", progressSeconds);
            result.put("progress_frames", progressFrames);
            if (start != null) {
                var begun = start.longValue();
                result.put("server_end_ms", serverEnd == null ? null : serverEnd - begun);
                result.put("server_outcome", outcome);
                result.put("harness_result_ms", harnessEnd == null ? null : harnessEnd - begun);
                result.put("harness_exit_ms", exit == null ? null : exit - begun);
                Long end = null;
                for (var candidate : new Long[]{serverEnd, harnessEnd}) {
                    if (candidate != null && (end == null || candidate < end)) {
                        end = candidate;
                    }
                }
                result.put("call_end_ms", end == null ? null : end - begun);
                result.put("bound_reached", end == null && exit == null);
            }
            result.put("output", HarnessOutput.parse(launcher.harness(), run.stdout()).toMap());
            result.put("stderr_tail", run.stderr().stream().skip(Math.max(0, run.stderr().size() - 10)).toList());
        }
        return result;
    }
}
