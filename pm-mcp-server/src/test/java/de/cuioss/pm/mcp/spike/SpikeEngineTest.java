/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;


import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("SpikeEngine")
class SpikeEngineTest {

    private static final String WORKER_A = "a";
    private static final String WORKER_B = "b";

    @TempDir
    Path runDir;

    private final AtomicLong clock = new AtomicLong();
    private final Set<String> closed = new HashSet<>();
    private SpikeEventLog log;

    @BeforeEach
    void setUp() {
        log = new SpikeEventLog(runDir);
    }

    private SpikeEngine engine(String scenario) {
        return new SpikeEngine(SpikeScenario.parse(new JsonObject(scenario)), log, clock::get,
                connection -> !closed.contains(connection));
    }

    private List<JsonObject> events(String name) throws IOException {
        return Files.readAllLines(runDir.resolve(SpikeEventLog.FILE_NAME)).stream().map(JsonObject::new)
                .filter(event -> name.equals(event.getString("event"))).toList();
    }

    private static String status(SpikeEngine.Pull pull) {
        return pull.body().getString(SpikeEngine.STATUS);
    }

    @Nested
    @DisplayName("wait loop")
    class WaitLoop {

        @Test
        @DisplayName("answers wait_again per wait step with a rising cycle, then done")
        void shouldCycleThenFinish() {
            var engine = engine("""
                    {"wait_seconds": 3, "steps": [{"kind": "wait", "repeat": 2}, {"kind": "done"}]}
                    """);

            var first = engine.pull(WORKER_A);
            var second = engine.pull(WORKER_A);
            var last = engine.pull(WORKER_A);

            assertEquals(SpikeEngine.WAIT_AGAIN, status(first));
            assertEquals(3_000, first.millis());
            assertEquals(1, first.body().getInteger("cycle"));
            assertEquals(2, second.body().getInteger("cycle"));
            assertEquals(SpikeEngine.DONE, status(last));
            assertEquals(0, last.millis());
            assertEquals(SpikeEngine.DONE, status(engine.pull(WORKER_A)));
        }

        @Test
        @DisplayName("adds a filler note of the requested size")
        void shouldPad() {
            var engine = engine("{\"steps\": [{\"kind\": \"wait\", \"pad_bytes\": 100}, {\"kind\": \"wait\"}]}");

            assertEquals(100, engine.pull(WORKER_A).body().getString("note").length());
            assertFalse(engine.pull(WORKER_A).body().containsKey("note"));
        }

        @Test
        @DisplayName("names the next call in every answer unless the scenario switches the hints off")
        void shouldHintNextCall() {
            var hinted = engine("{\"steps\": [{\"kind\": \"wait\"}, {\"kind\": \"task\", \"id\": \"t1\"}]}");
            var plain = engine("{\"next_hints\": false, \"steps\": [{\"kind\": \"wait\"}]}");

            assertEquals("call pull_wait", hinted.pull(WORKER_A).body().getString("next"));
            assertTrue(hinted.pull(WORKER_A).body().getString("next").startsWith("call pull_submit"));
            assertFalse(plain.pull(WORKER_A).body().containsKey("next"));
            assertFalse(plain.pull(WORKER_A).body().containsKey("next"));
        }

        @Test
        @DisplayName("reports done when the steps run out without a done step")
        void shouldFinishWithoutDoneStep() {
            var engine = engine("{\"steps\": []}");

            assertEquals(SpikeEngine.DONE, status(engine.pull(WORKER_A)));
        }
    }

    @Nested
    @DisplayName("tasks")
    class Tasks {

        private static final String TWO_TASKS = """
                {"wait_seconds": 1, "lease_seconds": 10, "steps": [
                  {"kind": "task", "id": "t1", "delay_seconds": 2, "task": {"q": "x"}},
                  {"kind": "task", "id": "t2"}]}
                """;

        @Test
        @DisplayName("delivers a task and accepts its submit from the same connection")
        void shouldDeliverAndAccept() throws Exception {
            var engine = engine(TWO_TASKS);

            var pull = engine.pull(WORKER_A);
            var submission = engine.submit(WORKER_A, "t1", "x", "because");

            assertEquals(SpikeEngine.TASK, status(pull));
            assertEquals(2_000, pull.millis());
            assertEquals("t1", pull.body().getString("task_id"));
            assertEquals("x", pull.body().getJsonObject("task").getString("q"));
            assertTrue(submission.accepted());
            assertEquals(1, engine.deliveries());
            assertEquals("new", events("delivery").getFirst().getString("reason"));
            assertEquals("because", events("submit").getFirst().getString("rationale"));
        }

        @ParameterizedTest(name = "{0} from {1} is refused as {2}")
        @CsvSource({"t1,b,not_bound", "t9,a,unknown_task", "t0,a,already_submitted"})
        @DisplayName("refuses a submit that does not match the binding")
        void shouldRefuse(String taskId, String connection, String reason) throws Exception {
            var engine = engine("""
                    {"steps": [{"kind": "task", "id": "t0"}, {"kind": "task", "id": "t1"}]}
                    """);
            engine.pull(WORKER_A);
            engine.submit(WORKER_A, "t0", "x", null);
            engine.pull(WORKER_A);

            var submission = engine.submit(connection, taskId, "x", null);

            assertFalse(submission.accepted());
            assertEquals(reason, submission.reason());
            assertEquals(reason, events("submit_refused").getFirst().getString("reason"));
        }

        @Test
        @DisplayName("delivers the same task again to a connection that pulls without submitting")
        void shouldRedeliverToSameConnection() throws Exception {
            var engine = engine(TWO_TASKS);
            engine.pull(WORKER_A);

            var again = engine.pull(WORKER_A);

            assertEquals("t1", again.body().getString("task_id"));
            assertEquals("same_connection", events("delivery").getLast().getString("reason"));
            assertEquals(2, events("delivery").getLast().getInteger("deliveries"));
        }

        @Test
        @DisplayName("delivers the task of a closed connection to the next caller exactly once")
        void shouldRedeliverAfterConnectionLoss() throws Exception {
            var engine = engine(TWO_TASKS);
            engine.pull(WORKER_A);
            closed.add(WORKER_A);

            var taken = engine.pull(WORKER_B);
            var late = engine.submit(WORKER_A, "t1", "x", null);
            var accepted = engine.submit(WORKER_B, "t1", "x", null);
            var next = engine.pull(WORKER_B);

            assertEquals("t1", taken.body().getString("task_id"));
            assertEquals("connection_closed", events("delivery").get(1).getString("reason"));
            assertFalse(late.accepted());
            assertEquals("not_bound", late.reason());
            assertTrue(accepted.accepted());
            assertEquals("t2", next.body().getString("task_id"));
        }

        @Test
        @DisplayName("delivers a task again once its lease has expired, not before")
        void shouldRedeliverAfterLeaseExpiry() throws Exception {
            var engine = engine(TWO_TASKS);
            engine.pull(WORKER_A);

            clock.set(9_999);
            var before = engine.pull(WORKER_B);
            clock.set(10_000);
            var after = engine.pull(WORKER_B);

            assertEquals("t2", before.body().getString("task_id"));
            assertEquals("t1", after.body().getString("task_id"));
            assertEquals("lease_expired", events("delivery").getLast().getString("reason"));
        }
    }

    @Nested
    @DisplayName("slots")
    class Slots {

        @Test
        @DisplayName("holds a task back while all slots are taken")
        void shouldBlockOnSlots() throws Exception {
            var engine = engine("""
                    {"wait_seconds": 4, "slots": 1, "steps": [
                      {"kind": "task", "id": "t1"}, {"kind": "task", "id": "t2"}]}
                    """);
            engine.pull(WORKER_A);

            var blocked = engine.pull(WORKER_B);
            engine.submit(WORKER_A, "t1", "x", null);
            var free = engine.pull(WORKER_B);

            assertEquals(SpikeEngine.WAIT_AGAIN, status(blocked));
            assertEquals(4_000, blocked.millis());
            assertEquals("t2", events("slot_blocked").getFirst().getString("task_id"));
            assertEquals("t2", free.body().getString("task_id"));
        }

        @Test
        @DisplayName("never delivers two writer tasks at the same time")
        void shouldAllowOneWriter() {
            var engine = engine("""
                    {"slots": 3, "steps": [
                      {"kind": "task", "id": "w1", "writer": true},
                      {"kind": "task", "id": "w2", "writer": true}]}
                    """);
            engine.pull(WORKER_A);

            var blocked = engine.pull(WORKER_B);

            assertEquals(SpikeEngine.WAIT_AGAIN, status(blocked));
        }

        @Test
        @DisplayName("keeps a caller waiting at the end while another connection still holds a task")
        void shouldNotFinishWithOpenLease() {
            var engine = engine("{\"steps\": [{\"kind\": \"task\", \"id\": \"t1\"}, {\"kind\": \"done\"}]}");
            engine.pull(WORKER_A);

            var waiting = engine.pull(WORKER_B);
            engine.submit(WORKER_A, "t1", "x", null);
            var done = engine.pull(WORKER_B);

            assertEquals(SpikeEngine.WAIT_AGAIN, status(waiting));
            assertEquals(SpikeEngine.DONE, status(done));
        }
    }
}
