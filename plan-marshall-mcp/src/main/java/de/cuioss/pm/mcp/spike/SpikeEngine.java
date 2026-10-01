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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;


import de.cuioss.pm.mcp.spike.SpikeScenario.Kind;
import de.cuioss.pm.mcp.spike.SpikeScenario.Step;
import io.vertx.core.json.JsonObject;

/**
 * Decides what a wait call answers and whether a submit is accepted.
 * <p>
 * All connections share one step queue. A delivered task is bound to the connection that pulled it until
 * it is submitted; a task whose connection is gone or whose lease expired is delivered again to the next
 * caller. The engine holds no timers: it names the duration a call has to block, the tool layer waits.
 */
final class SpikeEngine {

    static final String STATUS = "status";
    static final String WAIT_AGAIN = "wait_again";
    static final String TASK = "task";
    static final String DONE = "done";
    private static final String TASK_ID = "task_id";
    private static final String REASON = "reason";
    private static final String NEXT = "next";

    /**
     * Answer of one wait call.
     *
     * @param millis how long the call blocks before it answers
     * @param body   the answer handed to the model
     */
    record Pull(long millis, JsonObject body) {
    }

    /**
     * Outcome of one submit.
     *
     * @param accepted whether the submit was accepted
     * @param reason   {@code accepted}, {@code unknown_task}, {@code already_submitted} or {@code not_bound}
     */
    record Submission(boolean accepted, String reason) {
    }

    private record Lease(Step step, String connection, long deadline, int deliveries) {
    }

    private final SpikeScenario scenario;
    private final SpikeEventLog log;
    private final LongSupplier clock;
    private final Predicate<String> connectionAlive;
    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private final Set<String> submitted = new HashSet<>();
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
        this.scenario = scenario;
        this.log = log;
        this.clock = clock;
        this.connectionAlive = connectionAlive;
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
        var again = redeliver(connection);
        if (again != null) {
            return again;
        }
        var step = cursor < scenario.steps().size() ? scenario.steps().get(cursor) : null;
        if (step == null || step.kind() == Kind.DONE) {
            if (leases.isEmpty()) {
                return new Pull(0, hint(new JsonObject().put(STATUS, DONE), "stop, the work is finished"));
            }
            return waitAgain(scenario.waitMillis(), 0);
        }
        if (step.kind() == Kind.WAIT) {
            cursor++;
            return waitAgain(step.millis(), step.padBytes());
        }
        if (leases.size() >= scenario.slots() || step.writer() && leases.values().stream().anyMatch(l -> l.step().writer())) {
            log.log("slot_blocked", connection, new JsonObject().put(TASK_ID, step.taskId()));
            return waitAgain(scenario.waitMillis(), 0);
        }
        cursor++;
        return deliver(step, connection, 1, "new");
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
        var lease = leases.get(taskId);
        String refusal = null;
        if (lease == null) {
            refusal = submitted.contains(taskId) ? "already_submitted" : "unknown_task";
        } else if (!lease.connection().equals(connection)) {
            refusal = "not_bound";
        }
        if (refusal != null) {
            log.log("submit_refused", connection, new JsonObject().put(TASK_ID, taskId).put(REASON, refusal)
                    .put("decision", decision));
            return new Submission(false, refusal);
        }
        leases.remove(taskId);
        submitted.add(taskId);
        log.log("submit", connection, new JsonObject().put(TASK_ID, taskId).put("decision", decision)
                .put("rationale", rationale).put("deliveries", lease.deliveries()));
        return new Submission(true, "accepted");
    }

    private Pull redeliver(String connection) {
        var now = clock.getAsLong();
        for (var lease : leases.values()) {
            String reason = null;
            if (lease.connection().equals(connection)) {
                reason = "same_connection";
            } else if (!connectionAlive.test(lease.connection())) {
                reason = "connection_closed";
            } else if (now >= lease.deadline()) {
                reason = "lease_expired";
            }
            if (reason != null) {
                return deliver(lease.step(), connection, lease.deliveries() + 1, reason);
            }
        }
        return null;
    }

    private Pull deliver(Step step, String connection, int count, String reason) {
        leases.put(step.taskId(), new Lease(step, connection, clock.getAsLong() + scenario.leaseMillis(), count));
        deliveries++;
        log.log("delivery", connection, new JsonObject().put(TASK_ID, step.taskId()).put("deliveries", count)
                .put(REASON, reason).put("writer", step.writer()));
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
}
