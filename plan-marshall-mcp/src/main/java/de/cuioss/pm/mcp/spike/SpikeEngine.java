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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;


import de.cuioss.pm.mcp.spike.SpikeQueue.Caller;
import de.cuioss.pm.mcp.spike.SpikeQueue.Lease;
import de.cuioss.pm.mcp.spike.SpikeScenario.Ack;
import de.cuioss.pm.mcp.spike.SpikeScenario.Kind;
import de.cuioss.pm.mcp.spike.SpikeScenario.Offer;
import de.cuioss.pm.mcp.spike.SpikeScenario.Step;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Decides what a wait call answers and whether a submit is accepted.
 * <p>
 * Without supervision all connections share one step queue. A delivered task is bound to the connection that
 * pulled it until it is submitted; a task whose connection is gone or whose lease expired is delivered again
 * to the next caller. The engine holds no timers: it names the duration a call has to block, the tool layer
 * waits.
 * <p>
 * Under the supervised protocol ({@code "supervised": true}) a wait is held until a task is due for the
 * caller's role: the tool layer asks {@link #poll} again and again and answers as soon as it returns an
 * answer. An offer is leased to the caller's generation, may need an acknowledgement, and carries the
 * skills the generation has not received; the calls of a replaced generation are refused. {@link SpikeQueue}
 * holds the leases, {@link SpikeSkills} the catalogue, this class the decisions.
 */
final class SpikeEngine {

    static final String STATUS = "status";
    static final String WAIT_AGAIN = "wait_again";
    static final String TASK = "task";
    static final String DONE = "done";
    static final String END = "end";
    static final String ANSWER = "consultation_answer";
    static final String STALE = "stale_generation";
    static final String NOT_BOUND = "not_bound";
    static final String CONSULTANT = "consultant";
    static final String TASK_ID = "task_id";
    static final String CONSULTATION_ID = "consultation_id";
    private static final String REASON = "reason";
    private static final String NEXT = "next";
    private static final String DECISION = "decision";
    private static final String DELIVERIES = "deliveries";
    private static final String QUESTION = "question";
    private static final String ARGUMENTS = "arguments";
    private static final String TOOL = "tool";
    private static final String ACCEPTED = "accepted";

    /**
     * Answer of one wait call.
     *
     * @param millis how long the call blocks before it answers
     * @param body   the answer handed to the model
     */
    record Pull(long millis, JsonObject body) {
    }

    /**
     * Outcome of one submit or acknowledgement.
     *
     * @param accepted whether the call was accepted
     * @param reason   {@code accepted}, {@code unknown_task}, {@code already_submitted}, {@code not_bound} or
     *                 {@code stale_generation}
     */
    record Submission(boolean accepted, String reason) {
    }

    /**
     * Answer of a call that returns content or a refusal.
     *
     * @param accepted whether the call was accepted
     * @param body     the answer handed to the model
     */
    record Reply(boolean accepted, JsonObject body) {
    }

    private record Consultation(String id, String taskId, String decision, String rationale, boolean delivered) {

        boolean answered() {
            return decision != null;
        }
    }

    private final SpikeScenario scenario;
    private final SpikeEventLog log;
    private final LongSupplier clock;
    private final Predicate<String> connectionAlive;
    private final SpikeSkills skills;
    private final SpikeQueue queue = new SpikeQueue();
    private final Map<String, Consultation> consultations = new LinkedHashMap<>();
    private final Map<String, Integer> idle = new HashMap<>();
    private int cursor;
    private int cycles;
    private int deliveries;

    /**
     * @param scenario        the script of the run
     * @param log             the event log
     * @param clock           monotonic milliseconds
     * @param connectionAlive whether a connection is still open
     */
    SpikeEngine(SpikeScenario scenario, SpikeEventLog log, LongSupplier clock, Predicate<String> connectionAlive) {
        this(scenario, log, clock, connectionAlive, null);
    }

    /**
     * @param scenario        the script of the run
     * @param log             the event log
     * @param clock           monotonic milliseconds
     * @param connectionAlive whether a connection is still open
     * @param skills          the skill catalogue, {@code null} for none
     */
    SpikeEngine(SpikeScenario scenario, SpikeEventLog log, LongSupplier clock, Predicate<String> connectionAlive,
            SpikeSkills skills) {
        this.scenario = scenario;
        this.log = log;
        this.clock = clock;
        this.connectionAlive = connectionAlive;
        this.skills = skills;
        if (scenario.protocol().supervised()) {
            scenario.steps().stream().filter(step -> step.kind() == Kind.TASK).forEach(queue::add);
        }
    }

    /**
     * @return the number of task deliveries so far, redeliveries included
     */
    synchronized int deliveries() {
        return deliveries;
    }

    /**
     * Answers one wait call.
     *
     * @param connection the calling connection
     * @return what to answer and after how long
     */
    synchronized Pull pull(String connection) {
        var caller = Caller.of(connection);
        var again = redeliver(caller);
        if (again != null) {
            return again;
        }
        var step = cursor < scenario.steps().size() ? scenario.steps().get(cursor) : null;
        if (step == null || step.kind() == Kind.DONE) {
            if (queue.leases().isEmpty()) {
                return new Pull(0, hint(new JsonObject().put(STATUS, DONE), "stop, the work is finished"));
            }
            return waitAgain(scenario.waitMillis(), 0);
        }
        if (step.kind() == Kind.WAIT) {
            cursor++;
            return waitAgain(step.millis(), step.padBytes());
        }
        if (!admissible(step)) {
            log.log("slot_blocked", connection, new JsonObject().put(TASK_ID, step.taskId()));
            return waitAgain(scenario.waitMillis(), 0);
        }
        cursor++;
        return deliver(step, caller, "new");
    }

    /**
     * Records the result of a task.
     *
     * @param connection the calling connection
     * @param taskId     the task
     * @param decision   the decision taken
     * @param rationale  the reason given, may be {@code null}
     * @return whether the submit was accepted
     */
    synchronized Submission submit(String connection, String taskId, String decision, String rationale) {
        var caller = Caller.of(connection);
        var lease = queue.lease(taskId);
        var refusal = refusal(lease, caller, taskId);
        if (refusal != null) {
            log.log("submit_refused", connection, new JsonObject().put(TASK_ID, taskId).put(REASON, refusal)
                    .put(DECISION, decision));
            return new Submission(false, refusal);
        }
        queue.complete(taskId);
        log.log("submit", connection, new JsonObject().put(TASK_ID, taskId).put(DECISION, decision)
                .put("rationale", rationale).put(DELIVERIES, lease.deliveries()));
        return new Submission(true, ACCEPTED);
    }

    private String refusal(Lease lease, Caller caller, String taskId) {
        if (lease == null) {
            if (queue.submitted(taskId)) {
                return "already_submitted";
            }
            return queue.waits(taskId) ? NOT_BOUND : "unknown_task";
        }
        return lease.holder().same(caller) ? null : NOT_BOUND;
    }

    private boolean admissible(Step step) {
        var leases = queue.leases();
        // a consultation never waits for a slot: the asker holds one until the answer arrives
        return CONSULTANT.equals(step.offer().role()) || leases.size() < scenario.slots()
                && !(step.writer() && leases.stream().anyMatch(lease -> lease.step().writer()));
    }

    private Pull redeliver(Caller caller) {
        var now = clock.getAsLong();
        for (var lease : queue.leases()) {
            String reason = null;
            if (lease.holder().same(caller)) {
                reason = "same_connection";
            } else if (!connectionAlive.test(lease.holder().worker())) {
                reason = "connection_closed";
            } else if (now >= lease.deadline()) {
                reason = "lease_expired";
            }
            if (reason != null) {
                return deliver(lease.step(), caller, reason);
            }
        }
        return null;
    }

    private Pull deliver(Step step, Caller caller, String reason) {
        var now = clock.getAsLong();
        var lease = queue.bind(step, caller, now, now + scenario.leaseMillis());
        deliveries++;
        log.log("delivery", caller.worker(), new JsonObject().put(TASK_ID, step.taskId())
                .put(DELIVERIES, lease.deliveries()).put(REASON, reason).put("writer", step.writer()));
        return new Pull(step.millis(), hint(new JsonObject().put(STATUS, TASK).put(TASK_ID, step.taskId())
                .put(TASK, step.task()), "call pull_submit with this task_id, then call pull_wait"));
    }

    private Pull waitAgain(long millis, int padBytes) {
        cycles++;
        var body = new JsonObject().put(STATUS, WAIT_AGAIN).put("cycle", cycles);
        if (padBytes > 0) {
            body.put("note", "lorem ipsum ".repeat(padBytes / 12 + 1).substring(0, padBytes));
        }
        return new Pull(millis, hint(body, "call pull_wait"));
    }

    private JsonObject hint(JsonObject body, String next) {
        return scenario.nextHints() ? body.put(NEXT, next) : body;
    }

    // --- supervised protocol ------------------------------------------------------------------------

    /**
     * Answers a held wait call of the supervised protocol, or tells the tool layer to keep holding it.
     *
     * @param caller      the calling worker generation
     * @param heldMillis  how long the call has been held
     * @param skillsByUri whether the caller's connection activates skills itself
     * @return the answer, or {@code null} while the call stays held
     */
    synchronized Pull poll(Caller caller, long heldMillis, boolean skillsByUri) {
        var now = clock.getAsLong();
        sweep();
        if (queue.stale(caller)) {
            note("stale_refused", caller, new JsonObject().put("call", "wait"));
            return end(STALE, "stop, this worker was replaced");
        }
        var superseded = queue.enter(caller);
        if (!superseded.isEmpty()) {
            note("fenced", caller, new JsonObject().put(REASON, "superseded").put("released", new JsonArray(superseded)));
        }
        Lease own = null;
        var consulting = false;
        for (var lease : queue.leases()) {
            if (!lease.holder().same(caller)) {
                continue;
            }
            var consultation = consultations.get(lease.step().taskId());
            if (consultation == null || consultation.delivered()) {
                own = own == null ? lease : own;
            } else if (consultation.answered()) {
                return answer(caller, consultation);
            } else {
                consulting = true;
            }
        }
        if (own != null && !consulting) {
            return offer(own.step(), caller, "same_worker", skillsByUri);
        }
        var next = consulting ? null : queue.next(caller, now, this::admissible);
        if (next != null) {
            return offer(next.step(), caller, next.reason(), skillsByUri);
        }
        if (queue.drained()) {
            return end("finished", "stop, the work is finished");
        }
        if (heldMillis < scenario.waitMillis()) {
            return null;
        }
        cycles++;
        idle.merge(caller.worker(), 1, Integer::sum);
        return new Pull(0, hint(new JsonObject().put(STATUS, WAIT_AGAIN).put("cycle", cycles), "call pull_wait"));
    }

    /**
     * Gives back the leases whose acknowledgement or expiry is overdue and reports the tasks that became due.
     */
    synchronized void sweep() {
        var now = clock.getAsLong();
        var protocol = scenario.protocol();
        for (var taskId : queue.newlyDue(now)) {
            log.log("release_due", null, new JsonObject().put(TASK_ID, taskId));
        }
        for (var lease : queue.leases()) {
            String reason = null;
            if (protocol.ack() != Ack.NONE && !lease.acked() && now >= lease.offeredAt() + protocol.ackDeadlineMillis()) {
                reason = "ack_missed";
            } else if (now >= lease.deadline()) {
                reason = "lease_expired";
            }
            if (reason != null) {
                note(reason, lease.holder(), new JsonObject().put(TASK_ID, lease.step().taskId())
                        .put("waited_ms", now - lease.offeredAt()));
                queue.giveBack(lease.step().taskId(), reason);
            }
        }
    }

    /**
     * Confirms an offered task.
     *
     * @param caller the calling worker generation
     * @param taskId the task
     * @return whether the acknowledgement was accepted
     */
    synchronized Submission acknowledge(Caller caller, String taskId) {
        var refusal = bound(caller, taskId, "ack");
        if (refusal != null) {
            return new Submission(false, refusal);
        }
        confirm(caller, taskId, "explicit");
        return new Submission(true, ACCEPTED);
    }

    /**
     * Returns the facts and options of an offered task; under the implicit variant the call is the
     * acknowledgement.
     *
     * @param caller the calling worker generation
     * @param taskId the task
     * @return the task body or the refusal
     */
    synchronized Reply task(Caller caller, String taskId) {
        var refusal = bound(caller, taskId, TASK);
        if (refusal != null) {
            return refused(refusal);
        }
        if (scenario.protocol().ack() == Ack.IMPLICIT) {
            confirm(caller, taskId, "implicit");
        }
        var step = queue.lease(taskId).step();
        var body = new JsonObject().put(TASK_ID, taskId).put(TASK, step.task());
        if (scenario.protocol().links()) {
            body.put("links", links(step, false));
        }
        return new Reply(true, hint(body, "call pull_submit with this task_id, then call pull_wait"));
    }

    /**
     * Records the result of a task under the supervised protocol.
     *
     * @param caller         the calling worker generation
     * @param taskId         the task
     * @param decision       the decision taken
     * @param rationale      the reason given, may be {@code null}
     * @param consultationId the consultation the result refers to, may be {@code null}
     * @return whether the submit was accepted
     */
    synchronized Submission submit(Caller caller, String taskId, String decision, String rationale,
            String consultationId) {
        sweep();
        if (queue.stale(caller)) {
            note("stale_refused", caller, new JsonObject().put("call", "submit").put(TASK_ID, taskId)
                    .put(DECISION, decision));
            return new Submission(false, STALE);
        }
        var lease = queue.lease(taskId);
        var refusal = refusal(lease, caller, taskId);
        if (refusal != null) {
            note("submit_refused", caller, new JsonObject().put(TASK_ID, taskId).put(REASON, refusal)
                    .put(DECISION, decision));
            return new Submission(false, refusal);
        }
        queue.complete(taskId);
        var asked = consultations.values().stream().filter(c -> c.id().equals(taskId)).findFirst();
        if (asked.isPresent()) {
            var consultation = asked.get();
            consultations.put(consultation.taskId(),
                    new Consultation(consultation.id(), consultation.taskId(), decision, rationale, false));
            var asker = queue.lease(consultation.taskId());
            note("consult_answer", caller, new JsonObject().put(CONSULTATION_ID, taskId)
                    .put(TASK_ID, consultation.taskId()).put(DECISION, decision)
                    .put("asker", asker == null ? null : asker.holder().worker()));
            return new Submission(true, ACCEPTED);
        }
        var consultation = consultations.get(taskId);
        String reference = null;
        if (consultation != null) {
            reference = consultationId == null ? "missing" : consultation.id().equals(consultationId) ? "ok" : "wrong";
        }
        note("submit", caller, new JsonObject().put(TASK_ID, taskId).put(DECISION, decision)
                .put("rationale", rationale).put(DELIVERIES, lease.deliveries()).put("acked", lease.acked())
                .put("consultation_ref", reference));
        return new Submission(true, ACCEPTED);
    }

    /**
     * Opens a consultation: a task for the consultant role whose answer reaches the asker through its next
     * wait.
     *
     * @param caller   the asking worker generation
     * @param taskId   the task the question belongs to
     * @param question the question
     * @return the consultation id or the refusal
     */
    synchronized Reply consult(Caller caller, String taskId, String question) {
        var refusal = bound(caller, taskId, "consult");
        if (refusal != null) {
            return refused(refusal);
        }
        var existing = consultations.get(taskId);
        var id = existing == null ? "c-" + taskId : existing.id();
        if (existing == null) {
            var asked = queue.lease(taskId).step().task();
            var body = new JsonObject().put("kind", "consultation").put(QUESTION, question)
                    .put("facts", "Asked about this task: " + asked.getString(QUESTION, "") + " Facts: "
                            + asked.getString("facts", ""))
                    .put("options", asked.getValue("options"));
            queue.addFirst(new Step(Kind.TASK, 0, 0, id, false, body,
                    new Offer(CONSULTANT, 0, null, 0, List.of())));
            consultations.put(taskId, new Consultation(id, taskId, null, null, false));
            note("consult_open", caller, new JsonObject().put(CONSULTATION_ID, id).put(TASK_ID, taskId)
                    .put(QUESTION, question));
        } else {
            consultations.put(taskId, new Consultation(id, taskId, existing.decision(), existing.rationale(), false));
        }
        // the asker may wait for the consultant longer than a lease lasts
        queue.acknowledge(taskId, clock.getAsLong() + scenario.leaseMillis() + scenario.waitMillis());
        return new Reply(true, hint(new JsonObject().put(ACCEPTED, true).put(CONSULTATION_ID, id),
                "call pull_wait; the answer arrives there"));
    }

    /**
     * Declares a generation of a worker as replaced; its leases are given back at once.
     *
     * @param worker     the worker
     * @param generation the highest replaced generation
     * @return the tasks given back
     */
    synchronized JsonObject fence(String worker, int generation) {
        var released = new JsonArray(queue.fence(worker, generation));
        log.log("fenced", worker, new JsonObject().put("generation", generation).put(REASON, "fenced")
                .put("released", released));
        idle.remove(worker);
        return new JsonObject().put("worker", worker).put("generation", generation).put("released", released);
    }

    /**
     * @return what the driver sees: the queue, the leases, the idle wake-ups per worker, the consultations
     */
    synchronized JsonObject state() {
        sweep();
        var open = new JsonArray();
        consultations.values().forEach(c -> open.add(new JsonObject().put(CONSULTATION_ID, c.id())
                .put(TASK_ID, c.taskId()).put("answered", c.answered()).put("delivered", c.delivered())));
        var idleCounts = new JsonObject();
        idle.forEach(idleCounts::put);
        return queue.state(clock.getAsLong()).put("idle", idleCounts).put("consultations", open);
    }

    private String bound(Caller caller, String taskId, String call) {
        sweep();
        String refusal;
        if (queue.stale(caller)) {
            refusal = STALE;
            note("stale_refused", caller, new JsonObject().put("call", call).put(TASK_ID, taskId));
        } else {
            refusal = refusal(queue.lease(taskId), caller, taskId);
            if (refusal != null) {
                note(call + "_refused", caller, new JsonObject().put(TASK_ID, taskId).put(REASON, refusal));
            }
        }
        return refusal;
    }

    private void confirm(Caller caller, String taskId, String variant) {
        var lease = queue.lease(taskId);
        if (lease.acked()) {
            return;
        }
        var now = clock.getAsLong();
        queue.acknowledge(taskId, now + scenario.leaseMillis());
        note("ack", caller, new JsonObject().put(TASK_ID, taskId).put("variant", variant)
                .put("latency_ms", now - lease.offeredAt()));
    }

    private Pull offer(Step step, Caller caller, String reason, boolean skillsByUri) {
        var now = clock.getAsLong();
        var protocol = scenario.protocol();
        var lease = queue.bind(step, caller, now, now + scenario.leaseMillis());
        deliveries++;
        idle.put(caller.worker(), 0);
        var implicit = protocol.ack() == Ack.IMPLICIT;
        var body = new JsonObject().put(STATUS, TASK).put(TASK_ID, step.taskId())
                .put(TASK, implicit ? new JsonObject().put(QUESTION, step.task().getString(QUESTION)) : step.task());
        var delivered = 0;
        if (skills != null && !step.offer().skills().isEmpty()) {
            var delivery = skills.deliver(caller, step.offer().skills(), skillsByUri);
            body.put("skills", delivery.skills());
            for (var event : delivery.events()) {
                var name = (String) event.remove("event");
                note(name, caller, event.put(TASK_ID, step.taskId()));
                delivered += "skill_delivered".equals(name) ? 1 : 0;
            }
        }
        if (protocol.links()) {
            body.put("links", links(step, true));
        }
        note("offer", caller, new JsonObject().put(TASK_ID, step.taskId()).put(DELIVERIES, lease.deliveries())
                .put(REASON, reason).put("role", step.offer().role()).put("ack", protocol.ack().name().toLowerCase(Locale.ROOT))
                .put("skills", step.offer().skills().size()).put("skills_delivered", delivered));
        var next = switch (protocol.ack()) {
            case NONE -> "call pull_submit with this task_id, then call pull_wait";
            case EXPLICIT -> "call pull_ack with this task_id, then pull_submit, then pull_wait";
            case IMPLICIT -> "call pull_task with this task_id for the facts and options, then pull_submit, then pull_wait";
        };
        return new Pull(0, hint(body, next));
    }

    private JsonObject links(Step step, boolean offered) {
        var protocol = scenario.protocol();
        var id = new JsonObject().put(TASK_ID, step.taskId());
        var links = new JsonObject();
        if (offered && protocol.ack() == Ack.EXPLICIT) {
            links.put("ack", new JsonObject().put(TOOL, SpikeTools.PULL_ACK).put(ARGUMENTS, id.copy()));
        }
        if (offered && protocol.ack() == Ack.IMPLICIT) {
            links.put(TASK, new JsonObject().put(TOOL, SpikeTools.PULL_TASK).put(ARGUMENTS, id.copy()));
        }
        links.put("submit", new JsonObject().put(TOOL, SpikeTools.PULL_SUBMIT).put(ARGUMENTS, id.copy()
                .put(DECISION, "<one of the options>").put("rationale", "<one sentence>")));
        if (protocol.consult() && !CONSULTANT.equals(step.offer().role())) {
            links.put("consult", new JsonObject().put(TOOL, SpikeTools.PULL_CONSULT).put(ARGUMENTS, id.copy()
                    .put(QUESTION, "<your question to the other role>")));
        }
        return links;
    }

    private Pull answer(Caller caller, Consultation consultation) {
        consultations.put(consultation.taskId(), new Consultation(consultation.id(), consultation.taskId(),
                consultation.decision(), consultation.rationale(), true));
        note("consult_delivered", caller, new JsonObject().put(CONSULTATION_ID, consultation.id())
                .put(TASK_ID, consultation.taskId()));
        var body = new JsonObject().put(STATUS, ANSWER).put(CONSULTATION_ID, consultation.id())
                .put(TASK_ID, consultation.taskId()).put("answer", consultation.decision())
                .put("rationale", consultation.rationale());
        if (scenario.protocol().links()) {
            body.put("links", new JsonObject().put("submit", new JsonObject().put(TOOL, SpikeTools.PULL_SUBMIT)
                    .put(ARGUMENTS, new JsonObject().put(TASK_ID, consultation.taskId())
                            .put(DECISION, "<one of the options>").put("rationale", "<one sentence>")
                            .put(CONSULTATION_ID, consultation.id()))));
        }
        return new Pull(0, hint(body, "call pull_submit for this task_id with this consultation_id, then call pull_wait"));
    }

    private Pull end(String reason, String next) {
        return new Pull(0, hint(new JsonObject().put(STATUS, END).put(REASON, reason), next));
    }

    private static Reply refused(String reason) {
        return new Reply(false, new JsonObject().put(ACCEPTED, false).put(REASON, reason));
    }

    private void note(String event, Caller caller, JsonObject fields) {
        log.log(event, caller.worker(), fields.put("generation", caller.generation()));
    }
}
