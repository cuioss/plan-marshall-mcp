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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Fault scheduling of a gate-16 cell")
class FaultSchedulerTest {

    private static List<StubEvent> events(String cell) {
        try {
            var url = FaultSchedulerTest.class.getResource("/spike/partA/" + cell + "-events.jsonl");
            return StubEvent.readAll(Path.of(url.toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replays a recorded log through the scheduler, injecting every decision at once. */
    private static List<FaultScheduler.Injection> replay(Fault fault, String cell, int trials) {
        var events = events(cell);
        var scheduler = new FaultScheduler(fault, trials, 5_000, 300_000, events.getFirst().tMs() - 5_000);
        var injections = new ArrayList<FaultScheduler.Injection>();
        for (var event : events) {
            scheduler.onEvent(event, event.tMs(), (_, _) -> true).ifPresent(injection -> {
                injections.add(injection);
                scheduler.started(injection, event.tMs());
            });
        }
        return injections;
    }

    @Nested
    @DisplayName("triggers")
    class Triggers {

        @Test
        @DisplayName("kill-offer fires at the first fresh offer after the cooldown")
        void shouldTriggerAtOffer() {
            var injections = replay(Fault.KILL_OFFER, "kill-offer", 1);

            assertEquals(1, injections.size());
            assertEquals("f000-rem-01", injections.getFirst().taskId());
            assertEquals("w2", injections.getFirst().worker());
        }

        @Test
        @DisplayName("kill-exec and the stops fire at an acknowledgement")
        void shouldTriggerAtAck() {
            var injection = replay(Fault.STOP_LONG, "stop-long", 1).getFirst();

            assertEquals("f000-rem-01", injection.taskId());
            assertEquals(1791029824279L, injection.triggerMs());
        }

        @Test
        @DisplayName("relay-kill fires only at a held wait")
        void shouldTriggerAtHeldWait() {
            var injection = replay(Fault.RELAY_KILL, "relay-kill", 1).getFirst();

            assertEquals("w2", injection.worker());
            assertNull(injection.taskId());
        }

        @Test
        @DisplayName("never fires for an ineligible worker or during a running trial")
        void shouldRespectEligibilityAndRunningTrial() {
            var events = events("kill-offer");
            var scheduler = new FaultScheduler(Fault.KILL_OFFER, 10, 0, 300_000, 0);
            var offer = events.stream().filter(event -> "offer".equals(event.name())).findFirst().orElseThrow();

            assertTrue(scheduler.onEvent(offer, offer.tMs(), (_, _) -> false).isEmpty());
            var injection = scheduler.onEvent(offer, offer.tMs(), (_, _) -> true).orElseThrow();
            scheduler.started(injection, offer.tMs());
            assertTrue(scheduler.onEvent(offer, offer.tMs(), (_, _) -> true).isEmpty());
            assertEquals(injection, scheduler.active());
        }

        @Test
        @DisplayName("never faults the same task twice")
        void shouldNotRefaultTask() {
            var events = events("kill-offer");
            var scheduler = new FaultScheduler(Fault.KILL_OFFER, 10, 0, 1, 0);
            var offer = events.stream().filter(event -> "offer".equals(event.name())).findFirst().orElseThrow();
            scheduler.started(scheduler.onEvent(offer, offer.tMs(), (_, _) -> true).orElseThrow(), offer.tMs());
            assertTrue(scheduler.timeout(offer.tMs() + 10));

            assertEquals(Optional.empty(), scheduler.onEvent(offer, offer.tMs() + 20, (_, _) -> true));
        }
    }

    @Nested
    @DisplayName("settling")
    class Settling {

        @ParameterizedTest(name = "{0} on {1}")
        @CsvSource({"kill-offer,kill-offer,2", "kill-exec,kill-exec,1", "stop-short,stop-short,2",
                "stop-long,stop-long,1", "relay-kill,relay-kill,1"})
        @DisplayName("settles each trial and finishes the cell on the recorded Part A runs")
        void shouldSettleRecordedTrials(String fault, String cell, int trials) {
            var events = events(cell);
            var scheduler = new FaultScheduler(Fault.of(fault), trials, 5_000, 300_000, events.getFirst().tMs() - 5_000);
            for (var event : events) {
                scheduler.onEvent(event, event.tMs(), (_, _) -> true)
                        .ifPresent(injection -> scheduler.started(injection, event.tMs()));
            }

            assertEquals(trials, scheduler.started());
            assertTrue(scheduler.done(), "the last trial must have settled");
        }

        @Test
        @DisplayName("turn-end starts by itself and takes its task from the first offer to its generation")
        void shouldTrackTurnEnd() {
            var events = events("turn-end");
            var scheduler = new FaultScheduler(Fault.TURN_END, 1, 0, 300_000, 0);
            assertTrue(scheduler.turnEndDue(1));
            scheduler.started(new FaultScheduler.Injection(Fault.TURN_END, "w1", 2, null, 1), 1);
            assertFalse(scheduler.turnEndDue(2));

            events.forEach(event -> scheduler.onEvent(event, event.tMs(), (_, _) -> true));

            assertTrue(scheduler.done());
        }

        @Test
        @DisplayName("ends a trial at its timeout")
        void shouldTimeOut() {
            var scheduler = new FaultScheduler(Fault.KILL_EXEC, 1, 0, 1_000, 0);
            scheduler.started(new FaultScheduler.Injection(Fault.KILL_EXEC, "w1", 1, "t", 0), 0);

            assertFalse(scheduler.timeout(500));
            assertTrue(scheduler.timeout(1_001));
            assertTrue(scheduler.done());
        }
    }

    @Nested
    @DisplayName("fault ids")
    class Ids {

        @Test
        @DisplayName("parses all and lists")
        void shouldParse() {
            assertEquals(6, Fault.parse("all").size());
            assertEquals(List.of(Fault.STOP_LONG, Fault.RELAY_KILL), Fault.parse("stop-long, relay-kill"));
            assertThrows(IllegalArgumentException.class, () -> Fault.of("nope"));
        }
    }
}
