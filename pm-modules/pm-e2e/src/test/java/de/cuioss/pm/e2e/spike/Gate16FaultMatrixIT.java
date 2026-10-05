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

import java.util.LinkedHashMap;
import java.util.Map;


import de.cuioss.pm.e2e.spike.TrialEvaluator.Limits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Gate 16 (doc/roadmap/technical_linux.adoc L11; macOS without confinement): the E2 fault matrix of Part A against
 * the real relay {@code pm-mcp serve --job} and the real daemon. Per fault one cell with a fresh daemon and
 * {@code spike.workers} workers; {@code spike.trials} trials each. Figures:
 * {@code target/verification-results/gate16-<harness>.json}; the logs of every cell (stub events, supervisor,
 * harness output) under {@code target/spike-runs/}.
 * <p>
 * Opt-in: {@code -Dspike.harness=claude|opencode|codex -Dspike.item=gate16 [-Dspike.faults=all|kill-offer,…]
 * [-Dspike.trials=10]}. Expect hours: Part A took about an hour per harness and fault.
 */
@EnabledIfSystemProperty(named = SpikeSettings.HARNESS, matches = SpikeSettings.HARNESSES)
@EnabledIfSystemProperty(named = SpikeSettings.ITEM, matches = "gate16")
@DisplayName("Gate 16: E2 fault matrix against the real relay")
class Gate16FaultMatrixIT {

    @Test
    @DisplayName("detects, replaces and fences lost workers; every task is submitted exactly once")
    void shouldPassFaultMatrix() throws Exception {
        var settings = SpikeSettings.fromSystemProperties();
        var harness = settings.harness().id();
        var stage = SpikeStage.stage(SpikeResults.runDirectory("gate16-" + harness + "-stage"), settings.layout());
        var limits = new Limits(settings.silenceLimitMs(), settings.exitDetectMaxMs(), 5_000);
        var values = SpikeResults.environment(settings, stage);
        values.put("workers", settings.workers());
        values.put("trials_per_fault", settings.trials());
        values.put("wait_s", settings.waitSeconds());
        values.put("silence_grace_s", settings.graceSeconds());
        values.put("stop_short_s", settings.stopShortSeconds());
        values.put("stop_long_s", settings.stopLongSeconds());
        var cells = new LinkedHashMap<String, Object>();
        var pass = true;
        for (var fault : settings.faults()) {
            var cell = runCell(settings, stage, fault, limits);
            cells.put(fault.id(), cell);
            pass &= Boolean.TRUE.equals(cell.get("pass"));
            values.put("cells", cells);
            SpikeResults.write("gate16-" + harness, values, false);
        }
        var file = SpikeResults.write("gate16-" + harness, values, pass);

        assertTrue(pass, "gate 16 failed for " + harness + ", see " + file);
    }

    private static Map<String, Object> runCell(SpikeSettings settings, SpikeStage stage, Fault fault, Limits limits)
            throws Exception {
        var name = "gate16-" + settings.harness().id() + "-" + fault.id();
        var runDir = SpikeResults.runDirectory(name);
        var tasks = settings.cellDeadlineSeconds() / 10 + 20;
        var scenario = ScenarioBuilder.write(ScenarioBuilder.faultMatrix(settings.waitSeconds(), tasks),
                runDir.resolve("scenario.json"));
        var cell = new LinkedHashMap<String, Object>();
        try (var daemon = SpikeDaemon.start(stage, SpikeResults.base(), scenario, runDir)) {
            var launcher = new WorkerLauncher(settings, stage, daemon, runDir, SpikeResults.workspace(name));
            var tail = new EventTail(runDir.resolve("events.jsonl"));
            var supervisor = new Supervisor(settings, daemon, launcher, tail, runDir.resolve("supervisor.jsonl"));
            var aborted = supervisor.run(fault);
            tail.poll();
            var evaluation = TrialEvaluator.evaluate(tail.all(), supervisor.records(), limits);
            SpikeResults.json(runDir.resolve("trials.json"),
                    evaluation.trials().stream().map(TrialEvaluator.Trial::toMap).toList());
            cell.putAll(evaluation.toMap());
            cell.put("aborted", aborted);
            cell.put("generations", supervisor.workers().size());
            cell.put("run_dir", runDir.toString());
            cell.put("pass", aborted == null && evaluation.trials().size() >= settings.trials() && evaluation.pass());
            SpikeResults.json(runDir.resolve("result.json"), cell);
        }
        return cell;
    }
}
