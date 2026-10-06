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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


import de.cuioss.pm.e2e.spike.TrialEvaluator.Criterion;
import de.cuioss.pm.e2e.spike.TrialEvaluator.Limits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Gate-16 evaluation of a fault cell")
class TrialEvaluatorTest {

    /** The deadlines of Part A: bounded wait 20 s plus grace 30 s. */
    private static final Limits LIMITS = new Limits(50_000, 1_500, 5_000);

    private static List<StubEvent> fixture(String cell, String kind) {
        return fixture("partA", cell, kind);
    }

    private static List<StubEvent> fixture(String run, String cell, String kind) {
        try {
            var url = TrialEvaluatorTest.class.getResource("/spike/" + run + "/" + cell + "-" + kind + ".jsonl");
            return StubEvent.readAll(Path.of(url.toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TrialEvaluator.Evaluation evaluate(String cell) {
        return TrialEvaluator.evaluate(fixture(cell, "events"), fixture(cell, "supervisor"), LIMITS);
    }

    @Nested
    @DisplayName("on the archived Part A runs (Claude Code)")
    class PartA {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"kill-offer", "kill-exec", "stop-short", "stop-long", "turn-end", "relay-kill"})
        @DisplayName("passes every cell the evaluation reference records as passed")
        void shouldPassArchivedCells(String cell) {
            var evaluation = evaluate(cell);

            assertTrue(evaluation.pass(), () -> evaluation.toMap().toString());
            assertFalse(evaluation.trials().isEmpty());
        }

        @Test
        @DisplayName("measures the exit detection of kill-offer from the kill")
        void shouldMeasureExitDetection() {
            var trial = evaluate("kill-offer").trials().getFirst();

            assertEquals(Fault.KILL_OFFER, trial.fault());
            assertEquals("f000-rem-01", trial.taskId());
            assertEquals("exit", trial.detectReason());
            assertEquals(1791028938588L - 1791028938173L, trial.detectLatencyMs());
            assertEquals(List.of("f000-rem-01"), trial.released());
            assertEquals(1, trial.submits());
            assertEquals("w1/1", trial.submittedBy());
        }

        @Test
        @DisplayName("measures the silence of stop-long and counts the refused late calls")
        void shouldMeasureSilence() {
            var trial = evaluate("stop-long").trials().getFirst();

            assertEquals("silence", trial.detectReason());
            assertEquals(50_887L, trial.detectLatencyMs());
            assertEquals(2, trial.lateRefused());
        }

        @Test
        @DisplayName("takes the task of turn-end from the first offer to the faulted generation")
        void shouldFindTurnEndTask() {
            var trial = evaluate("turn-end").trials().getFirst();

            assertEquals("f001-rem-02", trial.taskId());
            assertNull(trial.detectLatencyMs(), "Part A recorded no exit time");
        }

        @Test
        @DisplayName("records the recovery of the worker after a relay kill")
        void shouldRecordRecovery() {
            var trials = evaluate("relay-kill").trials();

            assertEquals(1791031440820L, trials.getFirst().recoveredMs());
            assertEquals(2, trials.size());
        }
    }

    @Nested
    @DisplayName("on the Linux run of L14 (OpenCode)")
    class LinuxL14 {

        @Test
        @DisplayName("passes a short stall whose worker never continued and was replaced at the silence limit")
        void shouldPassShortStallReplacedAtTheLimit() {
            var evaluation = TrialEvaluator.evaluate(fixture("linuxL14", "stop-short", "events"),
                    fixture("linuxL14", "stop-short", "supervisor"), LIMITS);

            var hung = evaluation.trials().stream().filter(trial -> "f015-bui-16".equals(trial.taskId())).findFirst()
                    .orElseThrow();
            assertEquals(10, evaluation.trials().size());
            assertEquals("silence", hung.detectReason());
            assertEquals(50_029L, hung.detectLatencyMs());
            assertEquals(1, hung.submits());
            assertTrue(hung.pass(), hung.failures().toString());
            assertTrue(evaluation.pass(), evaluation.global().toString());
        }
    }

    @Nested
    @DisplayName("on violations")
    class Violations {

        private List<StubEvent> withLine(List<StubEvent> base, String line) {
            var all = new ArrayList<>(base);
            all.add(StubEvent.parse(line));
            return all;
        }

        @Test
        @DisplayName("fails exactly-once on a second submit of a task")
        void shouldFailDuplicateSubmit() {
            var events = withLine(fixture("kill-offer", "events"), """
                    {"t_ms":1791028946000,"event":"submit","connection":"w2","task_id":"f000-rem-01","generation":2}""");

            var evaluation = TrialEvaluator.evaluate(events, fixture("kill-offer", "supervisor"), LIMITS);

            assertFalse(evaluation.criteria().get(Criterion.EXACTLY_ONCE));
            assertFalse(evaluation.pass());
        }

        @Test
        @DisplayName("fails late-calls-refused when a replaced generation is still served")
        void shouldFailLateAcceptedCall() {
            var events = withLine(fixture("stop-long", "events"), """
                    {"t_ms":1791029895000,"event":"ack","connection":"w2","task_id":"f007-tri-03","generation":1}""");

            var evaluation = TrialEvaluator.evaluate(events, fixture("stop-long", "supervisor"), LIMITS);

            assertFalse(evaluation.criteria().get(Criterion.LATE_CALLS_REFUSED));
        }

        @Test
        @DisplayName("fails no-early-replacement when a silence limit was not reached")
        void shouldFailEarlySilence() {
            var strict = new Limits(60_000, 1_500, 5_000);

            var evaluation = TrialEvaluator.evaluate(fixture("stop-long", "events"), fixture("stop-long", "supervisor"),
                    strict);

            assertFalse(evaluation.criteria().get(Criterion.NO_EARLY_REPLACEMENT));
        }

        @Test
        @DisplayName("fails detection when a kill is detected later than the limit")
        void shouldFailSlowExitDetection() {
            var slow = new Limits(50_000, 300, 5_000);

            var evaluation = TrialEvaluator.evaluate(fixture("kill-offer", "events"),
                    fixture("kill-offer", "supervisor"), slow);

            assertFalse(evaluation.criteria().get(Criterion.DETECTION));
        }

        @Test
        @DisplayName("fails no-early-replacement when a short stall is replaced before the silence limit")
        void shouldFailReplacedShortStall() {
            var supervisor = withLine(fixture("stop-short", "supervisor"), """
                    {"t_ms":1791029560000,"event":"detect","worker":"w1","generation":1,"reason":"silence","silent_ms":5867}""");

            var evaluation = TrialEvaluator.evaluate(fixture("stop-short", "events"), supervisor, LIMITS);

            assertFalse(evaluation.criteria().get(Criterion.NO_EARLY_REPLACEMENT));
        }

        @Test
        @DisplayName("does not pass a cell without trials")
        void shouldNotPassEmptyCell() {
            assertFalse(TrialEvaluator.evaluate(List.of(), List.of(), LIMITS).pass());
        }
    }
}
