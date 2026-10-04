/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: FSL-1.1-ALv2
 *
 * Licensed under the Functional Source License, Version 1.1, ALv2 Future License
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE.md file at the root of this
 * repository or at https://github.com/cuioss/plan-marshall-mcp/blob/main/LICENSE.md
 */
package de.cuioss.pm.mcp.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;


import de.cuioss.pm.mcp.spike.SpikeQueue.Caller;
import de.cuioss.pm.mcp.spike.SpikeScenario.Kind;
import de.cuioss.pm.mcp.spike.SpikeScenario.Offer;
import de.cuioss.pm.mcp.spike.SpikeScenario.Step;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SpikeQueue")
class SpikeQueueTest {

    private static final Caller A1 = new Caller("a", 1, "worker");
    private static final Caller A2 = new Caller("a", 2, "worker");
    private static final Caller B1 = new Caller("b", 1, "worker");
    private static final Caller REVIEWER = new Caller("r", 1, "reviewer");

    private final SpikeQueue queue = new SpikeQueue();

    private static Step task(String id, Offer offer) {
        return new Step(Kind.TASK, 0, 0, id, false, new JsonObject(), offer);
    }

    private static Step task(String id) {
        return task(id, new Offer("worker", 0, null, 0, List.of()));
    }

    private String next(Caller caller, long now) {
        var entry = queue.next(caller, now, _ -> true);
        return entry == null ? null : entry.step().taskId();
    }

    @Nested
    @DisplayName("leases")
    class Leases {

        @Test
        @DisplayName("binds a task, counts its offers and completes it")
        void shouldBindAndComplete() {
            var step = task("t1");

            var first = queue.bind(step, A1, 5, 100);
            queue.acknowledge("t1", 200);
            var acked = queue.lease("t1");
            queue.complete("t1");

            assertEquals(1, first.deliveries());
            assertEquals(5, first.offeredAt());
            assertFalse(first.acked());
            assertTrue(acked.acked());
            assertEquals(200, acked.deadline());
            assertNull(queue.lease("t1"));
            assertTrue(queue.submitted("t1"));
            assertTrue(queue.drained());
        }

        @Test
        @DisplayName("offers a task that was given back first, to any worker of its role, with a rising count")
        void shouldGiveBack() {
            queue.add(task("t2"));
            queue.bind(task("t1", new Offer("worker", 0, "a", 1_000, List.of())), A1, 0, 100);

            queue.giveBack("t1", "ack_missed");
            queue.giveBack("unknown", "ack_missed");
            var waits = queue.waits("t1");
            var forReviewer = next(REVIEWER, 0);
            var entry = queue.next(B1, 0, _ -> false);
            var again = queue.bind(entry.step(), B1, 0, 100);

            assertTrue(waits);
            assertNull(forReviewer);
            assertEquals("t1", entry.step().taskId());
            assertEquals("ack_missed", entry.reason());
            assertTrue(entry.returned());
            assertEquals(2, again.deliveries());
            assertFalse(queue.waits("t1"));
        }

        @Test
        @DisplayName("puts a task in front of the waiting ones")
        void shouldAddFirst() {
            queue.add(task("t1"));
            queue.addFirst(task("t0"));

            assertEquals("t0", next(A1, 0));
            assertEquals("t1", next(A1, 0));
            assertNull(next(A1, 0));
        }
    }

    @Nested
    @DisplayName("eligibility")
    class Eligibility {

        @Test
        @DisplayName("offers a task only to its role, and a task without a role to anyone")
        void shouldFilterByRole() {
            queue.add(task("t1"));
            queue.add(task("open", Offer.ANY));

            assertEquals("open", next(REVIEWER, 0));
            assertEquals("t1", next(A1, 0));
        }

        @Test
        @DisplayName("keeps a task invisible until its release time")
        void shouldHonourRelease() {
            queue.add(task("t1", new Offer(null, 500, null, 0, List.of())));

            assertNull(next(A1, 499));
            assertEquals("t1", next(A1, 500));
        }

        @Test
        @DisplayName("offers a targeted task to its worker first and to others after the fallback time")
        void shouldTargetThenFallBack() {
            queue.add(task("t1", new Offer(null, 100, "a", 50, List.of())));

            assertNull(next(B1, 149));
            assertEquals("t1", next(B1, 150));
            queue.add(task("t2", new Offer(null, 100, "a", 50, List.of())));
            assertEquals("t2", next(A1, 100));
        }

        @Test
        @DisplayName("applies the caller's further condition to tasks that were never offered")
        void shouldApplyAdmissible() {
            queue.add(task("t1"));

            assertNull(queue.next(A1, 0, _ -> false));
            assertNotNull(queue.next(A1, 0, _ -> true));
        }

        @Test
        @DisplayName("reports every task once when it becomes due")
        void shouldReportNewlyDue() {
            queue.add(task("t1", new Offer(null, 0, null, 0, List.of())));
            queue.add(task("t2", new Offer(null, 100, null, 0, List.of())));

            assertEquals(List.of("t1"), queue.newlyDue(0));
            assertEquals(List.of(), queue.newlyDue(50));
            assertEquals(List.of("t2"), queue.newlyDue(100));
        }
    }

    @Nested
    @DisplayName("generations")
    class Generations {

        @Test
        @DisplayName("gives back the leases of a lower generation when a higher one enters")
        void shouldSupersede() {
            queue.enter(A1);
            queue.bind(task("t1"), A1, 0, 100);
            queue.bind(task("t2"), B1, 0, 100);

            var released = queue.enter(A2);
            var repeated = queue.enter(A2);

            assertEquals(List.of("t1"), released);
            assertEquals(List.of(), repeated);
            assertTrue(queue.stale(A1));
            assertFalse(queue.stale(A2));
            assertFalse(queue.stale(B1));
            assertNotNull(queue.lease("t2"));
            assertEquals("superseded", queue.next(A2, 0, _ -> false).reason());
        }

        @Test
        @DisplayName("fences a generation and all lower ones and gives back their leases")
        void shouldFence() {
            queue.bind(task("t1"), A1, 0, 100);

            var released = queue.fence("a", 1);
            queue.fence("a", 0);

            assertEquals(List.of("t1"), released);
            assertTrue(queue.stale(A1));
            assertFalse(queue.stale(A2));
            assertNull(queue.lease("t1"));
            assertEquals("fenced", queue.next(B1, 0, _ -> false).reason());
        }
    }

    @Test
    @DisplayName("shows the waiting tasks, the leases and the visible work per role")
    void shouldReportState() {
        queue.add(task("due"));
        queue.add(task("open", Offer.ANY));
        queue.add(task("later", new Offer("worker", 900, null, 0, List.of())));
        queue.bind(task("held"), A1, 10, 100);
        queue.bind(task("done"), B1, 10, 100);
        queue.complete("done");

        var state = queue.state(40);

        assertEquals(3, state.getJsonArray("waiting").size());
        assertFalse(state.getJsonArray("waiting").getJsonObject(2).getBoolean("due"));
        assertEquals(1, state.getJsonObject("work").getInteger("worker"));
        assertEquals(1, state.getJsonObject("work").getInteger("any"));
        var lease = state.getJsonArray("leases").getJsonObject(0);
        assertEquals("held", lease.getString("task_id"));
        assertEquals("a", lease.getString("worker"));
        assertEquals(1, lease.getInteger("generation"));
        assertEquals(30, lease.getLong("age_ms"));
        assertEquals(1, state.getInteger("submitted"));
        assertFalse(state.getBoolean("finished"));
    }
}
