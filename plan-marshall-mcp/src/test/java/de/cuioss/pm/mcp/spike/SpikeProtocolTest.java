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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;


import de.cuioss.pm.mcp.spike.SpikeQueue.Caller;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("SpikeEngine, supervised protocol")
class SpikeProtocolTest {

    private static final Caller A1 = new Caller("a", 1, "worker");
    private static final Caller A2 = new Caller("a", 2, "worker");
    private static final Caller B1 = new Caller("b", 1, "worker");
    private static final Caller CONSULTANT = new Caller("c", 1, SpikeEngine.CONSULTANT);
    private static final String SKILL = "skill://pm/role/triage/SKILL.md";
    private static final String TASK_ID = "task_id";
    private static final String TASKS = """
            "steps": [
              {"kind": "task", "id": "t1", "role": "worker", "task": {"question": "q1", "facts": "f1", "options": ["x", "y"]}},
              {"kind": "task", "id": "t2", "role": "worker", "task": {"question": "q2", "facts": "f2", "options": ["x", "y"]}}]}
            """;

    @TempDir
    Path runDir;

    private final AtomicLong clock = new AtomicLong();
    private SpikeEventLog log;
    private SpikeSkills skills;

    @BeforeEach
    void setUp() {
        log = new SpikeEventLog(runDir);
        skills = new SpikeSkills();
        skills.put(SKILL, "A bump is a fix.");
    }

    private SpikeEngine engine(String keys) {
        return engine(keys, TASKS);
    }

    private SpikeEngine engine(String keys, String steps) {
        var scenario = SpikeScenario.parse(new JsonObject(
                "{\"supervised\": true, \"wait_seconds\": 10, \"lease_seconds\": 100, " + keys + steps));
        return new SpikeEngine(scenario, log, clock::get, _ -> true, skills);
    }

    private List<JsonObject> events(String name) throws IOException {
        return Files.readAllLines(runDir.resolve(SpikeEventLog.FILE_NAME)).stream().map(JsonObject::new)
                .filter(event -> name.equals(event.getString("event"))).toList();
    }

    private static String status(SpikeEngine.Pull pull) {
        return pull.body().getString(SpikeEngine.STATUS);
    }

    @Nested
    @DisplayName("held wait")
    class HeldWait {

        @Test
        @DisplayName("stays held until a task is due, then offers it")
        void shouldHoldUntilDue() throws Exception {
            var engine = engine("", """
                    "steps": [{"kind": "task", "id": "t1", "release_at_seconds": 5, "task": {"question": "q"}}]}
                    """);

            var early = engine.poll(A1, 0, false);
            clock.set(5_000);
            var due = engine.poll(A1, 5_000, false);

            assertNull(early);
            assertEquals(SpikeEngine.TASK, status(due));
            assertEquals(0, due.millis());
            assertEquals("t1", due.body().getString(TASK_ID));
            assertEquals("q", due.body().getJsonObject("task").getString("question"));
            assertTrue(due.body().getString("next").startsWith("call pull_submit"));
            assertEquals("t1", events("release_due").getFirst().getString(TASK_ID));
            assertEquals(1, events("release_due").size());
            var offer = events("offer").getFirst();
            assertEquals("new", offer.getString("reason"));
            assertEquals("none", offer.getString("ack"));
            assertEquals(1, offer.getInteger("generation"));
            assertEquals(1, offer.getInteger("deliveries"));
            assertEquals(1, engine.deliveries());
        }

        @Test
        @DisplayName("answers wait_again after the bounded wait and counts the idle wake-ups")
        void shouldWaitAgain() {
            var engine = engine("", """
                    "steps": [{"kind": "task", "id": "t1", "release_at_seconds": 60}]}
                    """);

            var held = engine.poll(A1, 9_999, false);
            var first = engine.poll(A1, 10_000, false);
            var second = engine.poll(A1, 10_000, false);
            var idle = engine.state().getJsonObject("idle").getInteger("a");
            clock.set(60_000);
            engine.poll(A1, 0, false);

            assertNull(held);
            assertEquals(SpikeEngine.WAIT_AGAIN, status(first));
            assertEquals("call pull_wait", first.body().getString("next"));
            assertEquals(2, second.body().getInteger("cycle"));
            assertEquals(2, idle);
            assertEquals(0, engine.state().getJsonObject("idle").getInteger("a"));
        }

        @Test
        @DisplayName("offers only the tasks of the caller's role and ends when all are submitted")
        void shouldEndWhenFinished() throws Exception {
            var engine = engine("");

            var foreign = engine.poll(new Caller("r", 1, "reviewer"), 0, false);
            engine.poll(A1, 0, false);
            var first = engine.submit(A1, "t1", "x", "r", null);
            engine.poll(A1, 0, false);
            engine.submit(A1, "t2", "x", null, null);
            var end = engine.poll(A1, 0, false);

            assertNull(foreign);
            assertTrue(first.accepted());
            assertEquals(SpikeEngine.END, status(end));
            assertEquals("finished", end.body().getString("reason"));
            assertEquals(2, events("submit").size());
            assertFalse(events("submit").getFirst().getBoolean("acked"));
            assertNull(events("submit").getFirst().getString("consultation_ref"));
            assertTrue(engine.state().getBoolean("finished"));
        }

        @Test
        @DisplayName("offers the same task again to a worker that waits without submitting")
        void shouldReofferToSameWorker() throws Exception {
            var engine = engine("");
            engine.poll(A1, 0, false);

            var again = engine.poll(A1, 0, false);

            assertEquals("t1", again.body().getString(TASK_ID));
            assertEquals("same_worker", events("offer").getLast().getString("reason"));
            assertEquals(2, events("offer").getLast().getInteger("deliveries"));
        }

        @Test
        @DisplayName("holds a task back while all slots are taken")
        void shouldRespectSlots() {
            var engine = engine("\"slots\": 1, ");
            engine.poll(A1, 0, false);

            var blocked = engine.poll(B1, 0, false);
            engine.submit(A1, "t1", "x", null, null);
            var free = engine.poll(B1, 0, false);

            assertNull(blocked);
            assertEquals("t2", free.body().getString(TASK_ID));
        }

        @Test
        @DisplayName("offers a targeted task to another worker only after the fallback time")
        void shouldFallBack() {
            var engine = engine("", """
                    "steps": [{"kind": "task", "id": "t1", "offer_to": "a", "fallback_after_seconds": 5}]}
                    """);

            var early = engine.poll(B1, 0, false);
            clock.set(5_000);
            var late = engine.poll(B1, 0, false);

            assertNull(early);
            assertEquals("t1", late.body().getString(TASK_ID));
        }
    }

    @Nested
    @DisplayName("acknowledgement")
    class Acknowledgement {

        @Test
        @DisplayName("accepts an explicit acknowledgement once and records its latency")
        void shouldAckExplicitly() throws Exception {
            var engine = engine("\"ack\": \"explicit\", \"links\": true, \"next_hints\": false, ");
            var offer = engine.poll(A1, 0, false);
            clock.set(700);

            var ack = engine.acknowledge(A1, "t1");
            var repeated = engine.acknowledge(A1, "t1");
            var foreign = engine.acknowledge(B1, "t1");
            var unknown = engine.acknowledge(A1, "t9");
            engine.submit(A1, "t1", "x", null, null);

            assertTrue(ack.accepted());
            assertTrue(repeated.accepted());
            assertFalse(foreign.accepted());
            assertEquals(SpikeEngine.NOT_BOUND, foreign.reason());
            assertEquals("unknown_task", unknown.reason());
            assertFalse(offer.body().containsKey("next"));
            var links = offer.body().getJsonObject("links");
            assertEquals(SpikeTools.PULL_ACK, links.getJsonObject("ack").getString("tool"));
            assertEquals("t1", links.getJsonObject("ack").getJsonObject("arguments").getString(TASK_ID));
            assertEquals(SpikeTools.PULL_SUBMIT, links.getJsonObject("submit").getString("tool"));
            assertFalse(links.containsKey("task"));
            assertFalse(links.containsKey("consult"));
            assertEquals(1, events("ack").size());
            assertEquals(700, events("ack").getFirst().getLong("latency_ms"));
            assertEquals("explicit", events("ack").getFirst().getString("variant"));
            assertEquals(2, events("ack_refused").size());
            assertTrue(events("submit").getFirst().getBoolean("acked"));
        }

        @Test
        @DisplayName("offers the question only and counts the task call as the acknowledgement")
        void shouldAckImplicitly() throws Exception {
            var engine = engine("\"ack\": \"implicit\", \"links\": true, ");

            var offer = engine.poll(A1, 0, false);
            var task = engine.task(A1, "t1");
            engine.task(A1, "t1");
            var foreign = engine.task(B1, "t1");

            assertEquals("q1", offer.body().getJsonObject("task").getString("question"));
            assertFalse(offer.body().getJsonObject("task").containsKey("facts"));
            assertTrue(offer.body().getString("next").startsWith("call pull_task"));
            assertEquals(SpikeTools.PULL_TASK,
                    offer.body().getJsonObject("links").getJsonObject("task").getString("tool"));
            assertTrue(task.accepted());
            assertEquals("f1", task.body().getJsonObject("task").getString("facts"));
            assertFalse(task.body().getJsonObject("links").containsKey("task"));
            assertTrue(task.body().getJsonObject("links").containsKey("submit"));
            assertFalse(foreign.accepted());
            assertEquals(SpikeEngine.NOT_BOUND, foreign.body().getString("reason"));
            assertEquals(1, events("ack").size());
            assertEquals("implicit", events("ack").getFirst().getString("variant"));
        }

        @Test
        @DisplayName("returns the task body without an acknowledgement when none is required")
        void shouldReturnTaskWithoutAck() throws Exception {
            var engine = engine("");
            engine.poll(A1, 0, false);

            var task = engine.task(A1, "t1");

            assertTrue(task.accepted());
            assertFalse(task.body().containsKey("links"));
            assertTrue(events("ack").isEmpty());
        }

        @Test
        @DisplayName("gives a task back when its acknowledgement is overdue and offers it again")
        void shouldReleaseOnMissedAck() throws Exception {
            var engine = engine("\"ack\": \"explicit\", \"ack_deadline_seconds\": 3, ");
            engine.poll(A1, 0, false);

            clock.set(2_999);
            engine.sweep();
            var before = events("ack_missed").size();
            clock.set(3_000);
            var again = engine.poll(B1, 0, false);
            var late = engine.submit(A1, "t1", "x", null, null);
            var lateAck = engine.acknowledge(A1, "t1");

            assertEquals(0, before);
            assertEquals("t1", again.body().getString(TASK_ID));
            assertEquals("a", events("ack_missed").getFirst().getString("connection"));
            assertEquals(3_000, events("ack_missed").getFirst().getLong("waited_ms"));
            assertEquals("ack_missed", events("offer").getLast().getString("reason"));
            assertEquals(2, events("offer").getLast().getInteger("deliveries"));
            assertFalse(late.accepted());
            assertEquals(SpikeEngine.NOT_BOUND, late.reason());
            assertFalse(lateAck.accepted());
        }

        @Test
        @DisplayName("keeps an acknowledged task until its lease expires, then offers it again")
        void shouldExpireLease() throws Exception {
            var engine = engine("\"ack\": \"explicit\", \"ack_deadline_seconds\": 3, ");
            engine.poll(A1, 0, false);
            clock.set(1_000);
            engine.acknowledge(A1, "t1");

            clock.set(100_999);
            engine.sweep();
            var held = engine.state().getJsonArray("leases").size();
            clock.set(101_000);
            var again = engine.poll(B1, 0, false);

            assertEquals(1, held);
            assertEquals("t1", again.body().getString(TASK_ID));
            assertEquals("lease_expired", events("offer").getLast().getString("reason"));
            assertEquals(1, events("lease_expired").size());
        }
    }

    @Nested
    @DisplayName("generations")
    class Generations {

        @Test
        @DisplayName("releases the lease at once on a fence and gives the successor the task exactly once")
        void shouldFence() throws Exception {
            var engine = engine("");
            engine.poll(A1, 0, false);

            var fence = engine.fence("a", 1);
            var wait = engine.poll(A1, 0, false);
            var late = engine.submit(A1, "t1", "x", null, null);
            var successor = engine.poll(A2, 0, false);
            var accepted = engine.submit(A2, "t1", "x", null, null);
            var next = engine.poll(A2, 0, false);

            assertEquals(List.of("t1"), fence.getJsonArray("released").getList());
            assertEquals(SpikeEngine.END, status(wait));
            assertEquals(SpikeEngine.STALE, wait.body().getString("reason"));
            assertFalse(late.accepted());
            assertEquals(SpikeEngine.STALE, late.reason());
            assertEquals("t1", successor.body().getString(TASK_ID));
            assertTrue(accepted.accepted());
            assertEquals("t2", next.body().getString(TASK_ID));
            assertEquals("fenced", events("fenced").getFirst().getString("reason"));
            assertEquals("fenced", events("offer").get(1).getString("reason"));
            assertEquals(List.of("wait", "submit"),
                    events("stale_refused").stream().map(event -> event.getString("call")).toList());
            assertEquals(1, events("submit").stream().filter(e -> "t1".equals(e.getString(TASK_ID))).count());
        }

        @Test
        @DisplayName("treats the first call of a higher generation as the fence of the lower one")
        void shouldSupersede() throws Exception {
            var engine = engine("\"ack\": \"explicit\", \"consult\": true, ");
            engine.poll(A1, 0, false);

            var successor = engine.poll(A2, 0, false);
            var ack = engine.acknowledge(A1, "t1");
            var task = engine.task(A1, "t1");
            var consult = engine.consult(A1, "t1", "?");

            assertEquals("t1", successor.body().getString(TASK_ID));
            assertEquals("superseded", events("fenced").getFirst().getString("reason"));
            assertEquals("superseded", events("offer").getLast().getString("reason"));
            assertEquals(SpikeEngine.STALE, ack.reason());
            assertEquals(SpikeEngine.STALE, task.body().getString("reason"));
            assertFalse(consult.accepted());
            assertEquals(3, events("stale_refused").size());
        }

        @ParameterizedTest(name = "{0} from {1} is refused as {2}")
        @CsvSource({"t1,b,not_bound", "t9,a,unknown_task", "t0,a,already_submitted", "t2,a,not_bound"})
        @DisplayName("refuses a submit that does not match the lease")
        void shouldRefuse(String taskId, String worker, String reason) throws Exception {
            var engine = engine("", """
                    "steps": [{"kind": "task", "id": "t0"}, {"kind": "task", "id": "t1"}, {"kind": "task", "id": "t2"}]}
                    """);
            engine.poll(A1, 0, false);
            engine.submit(A1, "t0", "x", null, null);
            engine.poll(A1, 0, false);

            var submission = engine.submit(new Caller(worker, 1, "worker"), taskId, "x", null, null);

            assertFalse(submission.accepted());
            assertEquals(reason, submission.reason());
            assertEquals(reason, events("submit_refused").getFirst().getString("reason"));
        }
    }

    @Nested
    @DisplayName("consultation")
    class Consultation {

        private static final String KEYS = "\"consult\": true, \"links\": true, \"slots\": 1, ";

        @Test
        @DisplayName("runs the question as a task of the consultant and answers the asker's wait")
        void shouldConsult() throws Exception {
            var engine = engine(KEYS);
            var offer = engine.poll(A1, 0, false);

            var opened = engine.consult(A1, "t1", "is x right?");
            var waiting = engine.poll(A1, 0, false);
            var timedOut = engine.poll(A1, 10_000, false);
            var question = engine.poll(CONSULTANT, 0, false);
            var answered = engine.submit(CONSULTANT, "c-t1", "y", "because", null);
            var answer = engine.poll(A1, 0, false);
            var submitted = engine.submit(A1, "t1", "y", "as advised", "c-t1");
            var consultantIdle = engine.poll(CONSULTANT, 0, false);

            assertTrue(offer.body().getJsonObject("links").containsKey("consult"));
            assertTrue(opened.accepted());
            assertEquals("c-t1", opened.body().getString(SpikeEngine.CONSULTATION_ID));
            assertNull(waiting);
            assertEquals(SpikeEngine.WAIT_AGAIN, status(timedOut));
            assertEquals("c-t1", question.body().getString(TASK_ID));
            assertEquals("is x right?", question.body().getJsonObject("task").getString("question"));
            assertTrue(question.body().getJsonObject("task").getString("facts").contains("f1"));
            assertFalse(question.body().getJsonObject("links").containsKey("consult"));
            assertTrue(answered.accepted());
            assertEquals(SpikeEngine.ANSWER, status(answer));
            assertEquals("y", answer.body().getString("answer"));
            assertEquals("because", answer.body().getString("rationale"));
            assertEquals("c-t1", answer.body().getJsonObject("links").getJsonObject("submit")
                    .getJsonObject("arguments").getString(SpikeEngine.CONSULTATION_ID));
            assertTrue(submitted.accepted());
            assertNull(consultantIdle);
            assertEquals("is x right?", events("consult_open").getFirst().getString("question"));
            assertEquals("a", events("consult_answer").getFirst().getString("asker"));
            assertEquals("t1", events("consult_answer").getFirst().getString(TASK_ID));
            assertEquals(1, events("consult_delivered").size());
            assertEquals("ok", events("submit").getFirst().getString("consultation_ref"));
            var state = engine.state().getJsonArray("consultations").getJsonObject(0);
            assertTrue(state.getBoolean("answered"));
            assertTrue(state.getBoolean("delivered"));
        }

        @ParameterizedTest(name = "consultation_id {0} is recorded as {1}")
        @CsvSource(value = {"NULL,missing", "c-other,wrong"}, nullValues = "NULL")
        @DisplayName("records a final submit that does not refer to its consultation")
        void shouldRecordReference(String consultationId, String expected) throws Exception {
            var engine = engine(KEYS);
            engine.poll(A1, 0, false);
            engine.consult(A1, "t1", "?");
            engine.poll(CONSULTANT, 0, false);
            engine.submit(CONSULTANT, "c-t1", "y", null, null);
            engine.poll(A1, 0, false);

            engine.submit(A1, "t1", "y", null, consultationId);

            assertEquals(expected, events("submit").getFirst().getString("consultation_ref"));
        }

        @Test
        @DisplayName("delivers the answer to the successor of an asker that was replaced")
        void shouldRebindToSuccessor() throws Exception {
            var engine = engine(KEYS);
            engine.poll(A1, 0, false);
            engine.consult(A1, "t1", "?");
            engine.poll(CONSULTANT, 0, false);
            engine.submit(CONSULTANT, "c-t1", "y", null, null);
            engine.fence("a", 1);

            var reoffer = engine.poll(A2, 0, false);
            var again = engine.consult(A2, "t1", "?");
            var answer = engine.poll(A2, 0, false);
            var after = engine.poll(A2, 0, false);

            assertEquals("t1", reoffer.body().getString(TASK_ID));
            assertEquals("c-t1", again.body().getString(SpikeEngine.CONSULTATION_ID));
            assertEquals(SpikeEngine.ANSWER, status(answer));
            assertEquals(SpikeEngine.TASK, status(after));
            assertEquals(1, events("consult_open").size());
        }

        @Test
        @DisplayName("refuses a consultation about a task the caller does not hold")
        void shouldRefuseForeignConsult() throws Exception {
            var engine = engine(KEYS);
            engine.poll(A1, 0, false);

            var reply = engine.consult(B1, "t1", "?");

            assertFalse(reply.accepted());
            assertEquals(SpikeEngine.NOT_BOUND, reply.body().getString("reason"));
            assertEquals(1, events("consult_refused").size());
        }
    }

    @Nested
    @DisplayName("skills")
    class Skills {

        private static final String STEPS = """
                "steps": [
                  {"kind": "task", "id": "t1", "skills": ["skill://pm/role/triage/SKILL.md"]},
                  {"kind": "task", "id": "t2", "skills": ["skill://pm/role/triage/SKILL.md", "skill://pm/none/SKILL.md"]},
                  {"kind": "task", "id": "t3"}]}
                """;

        @Test
        @DisplayName("carries a required skill in-band in the first offer of a generation only")
        void shouldDeliverInOffer() throws Exception {
            var engine = engine("", STEPS);

            var first = engine.poll(A1, 0, false);
            engine.submit(A1, "t1", "x", null, null);
            var second = engine.poll(A1, 0, false);
            engine.submit(A1, "t2", "x", null, null);
            var third = engine.poll(A1, 0, false);

            var skill = first.body().getJsonArray("skills").getJsonObject(0);
            assertEquals(SKILL, skill.getString("uri"));
            assertEquals("A bump is a fix.", skill.getString("content"));
            assertNotNull(skill.getString("digest"));
            assertFalse(second.body().getJsonArray("skills").getJsonObject(0).containsKey("content"));
            assertTrue(second.body().getJsonArray("skills").getJsonObject(1).getBoolean("missing"));
            assertFalse(third.body().containsKey("skills"));
            assertEquals(1, events("skill_delivered").size());
            assertEquals("inband", events("skill_delivered").getFirst().getString("form"));
            assertEquals("t1", events("skill_delivered").getFirst().getString(TASK_ID));
            assertEquals(1, events("skill_missing").size());
            assertEquals(1, events("offer").getFirst().getInteger("skills_delivered"));
            assertEquals(2, events("offer").get(1).getInteger("skills"));
        }

        @Test
        @DisplayName("names the skill by URI only for a connection that activates skills itself")
        void shouldDeliverByUri() throws Exception {
            var engine = engine("", STEPS);

            var offer = engine.poll(A1, 0, true);

            assertFalse(offer.body().getJsonArray("skills").getJsonObject(0).containsKey("content"));
            assertEquals("uri", events("skill_delivered").getFirst().getString("form"));
        }

        @Test
        @DisplayName("offers without a skills entry when the run has no catalogue")
        void shouldOfferWithoutCatalogue() {
            var scenario = SpikeScenario.parse(new JsonObject("{\"supervised\": true, " + STEPS));
            var engine = new SpikeEngine(scenario, log, clock::get, _ -> true);

            var offer = engine.poll(A1, 0, false);

            assertFalse(offer.body().containsKey("skills"));
        }
    }
}
