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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;


import de.cuioss.pm.mcp.spike.SpikeScenario.Step;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The tasks of a run and who holds them: waiting tasks, leases, generations and fences.
 * <p>
 * A lease binds a task to one caller, which is a worker in one generation. A task whose lease is given back
 * returns to the front of the queue and is open to every worker of its role. A worker is stale when a higher
 * generation of the same worker has called, or when the driver fenced its generation; the leases of a stale
 * generation are given back at that moment, not at their expiry.
 * <p>
 * The queue is not thread-safe; {@link SpikeEngine} serialises all access.
 */
final class SpikeQueue {

    private static final String TASK_ID = "task_id";

    /**
     * Who calls: a worker in one generation.
     *
     * @param worker     the worker id, or the connection id of a caller without one
     * @param generation the generation of the worker process, {@code 0} without supervision
     * @param role       the role whose tasks the worker pulls, {@code null} for any
     */
    record Caller(String worker, int generation, String role) {

        /**
         * @param connection the connection or worker id
         * @return a caller outside the supervised protocol
         */
        static Caller of(String connection) {
            return new Caller(connection, 0, null);
        }

        boolean same(Caller other) {
            return worker.equals(other.worker) && generation == other.generation;
        }
    }

    /**
     * A task bound to a caller.
     *
     * @param step       the task
     * @param holder     the caller the task is bound to
     * @param deadline   when the lease expires
     * @param deliveries how often the task has been offered, this offer included
     * @param offeredAt  when the task was offered
     * @param acked      whether the holder confirmed the task
     */
    record Lease(Step step, Caller holder, long deadline, int deliveries, long offeredAt, boolean acked) {
    }

    /**
     * A task that waits to be offered.
     *
     * @param step   the task
     * @param reason why it waits: {@code new}, or the reason its last lease was given back
     */
    record Waiting(Step step, String reason) {

        boolean returned() {
            return !"new".equals(reason);
        }
    }

    private final List<Waiting> waiting = new ArrayList<>();
    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private final Set<String> submitted = new HashSet<>();
    private final Set<String> announced = new HashSet<>();
    private final Map<String, Integer> offers = new HashMap<>();
    private final Map<String, Integer> current = new HashMap<>();
    private final Map<String, Integer> fenced = new HashMap<>();

    /**
     * Appends a task that has not been offered yet.
     *
     * @param step the task
     */
    void add(Step step) {
        waiting.add(new Waiting(step, "new"));
    }

    /**
     * Puts a task in front of all waiting tasks.
     *
     * @param step the task
     */
    void addFirst(Step step) {
        waiting.addFirst(new Waiting(step, "new"));
    }

    Collection<Lease> leases() {
        return List.copyOf(leases.values());
    }

    Lease lease(String taskId) {
        return leases.get(taskId);
    }

    boolean submitted(String taskId) {
        return submitted.contains(taskId);
    }

    /**
     * @param taskId the task
     * @return whether the task waits to be offered
     */
    boolean waits(String taskId) {
        return waiting.stream().anyMatch(entry -> entry.step().taskId().equals(taskId));
    }

    /**
     * @return whether no task waits and none is leased
     */
    boolean drained() {
        return waiting.isEmpty() && leases.isEmpty();
    }

    /**
     * Binds a task to a caller.
     *
     * @param step     the task
     * @param holder   the caller
     * @param now      the time of the offer
     * @param deadline when the lease expires
     * @return the lease
     */
    Lease bind(Step step, Caller holder, long now, long deadline) {
        var lease = new Lease(step, holder, deadline, offers.merge(step.taskId(), 1, Integer::sum), now, false);
        leases.put(step.taskId(), lease);
        return lease;
    }

    /**
     * Marks a lease as confirmed and moves its expiry.
     *
     * @param taskId   the task
     * @param deadline the new expiry
     */
    void acknowledge(String taskId, long deadline) {
        leases.computeIfPresent(taskId, (_, lease) -> new Lease(lease.step(), lease.holder(), deadline,
                lease.deliveries(), lease.offeredAt(), true));
    }

    /**
     * Ends a lease with an accepted result.
     *
     * @param taskId the task
     */
    void complete(String taskId) {
        leases.remove(taskId);
        submitted.add(taskId);
    }

    /**
     * Ends a lease without a result; the task is offered again, to any worker of its role.
     *
     * @param taskId the task
     * @param reason why the lease ends
     */
    void giveBack(String taskId, String reason) {
        var lease = leases.remove(taskId);
        if (lease != null) {
            waiting.addFirst(new Waiting(lease.step(), reason));
        }
    }

    /**
     * @param caller the caller
     * @return whether a higher generation replaced the caller or the driver fenced it
     */
    boolean stale(Caller caller) {
        return caller.generation() < current.getOrDefault(caller.worker(), caller.generation())
                || caller.generation() <= fenced.getOrDefault(caller.worker(), Integer.MIN_VALUE);
    }

    /**
     * Notes the generation of a caller; the first call of a higher generation gives back what lower ones held.
     *
     * @param caller the caller
     * @return the tasks given back
     */
    List<String> enter(Caller caller) {
        if (caller.generation() <= current.getOrDefault(caller.worker(), Integer.MIN_VALUE)) {
            return List.of();
        }
        current.put(caller.worker(), caller.generation());
        return release(caller.worker(), caller.generation() - 1, "superseded");
    }

    /**
     * Declares a generation of a worker and all lower ones as replaced and gives back what they held.
     *
     * @param worker     the worker
     * @param generation the highest replaced generation
     * @return the tasks given back
     */
    List<String> fence(String worker, int generation) {
        fenced.merge(worker, generation, Math::max);
        return release(worker, generation, "fenced");
    }

    private List<String> release(String worker, int upTo, String reason) {
        var released = leases.values().stream()
                .filter(lease -> lease.holder().worker().equals(worker) && lease.holder().generation() <= upTo)
                .map(lease -> lease.step().taskId()).toList();
        released.forEach(taskId -> giveBack(taskId, reason));
        return released;
    }

    /**
     * Takes the first task the caller may be offered.
     *
     * @param caller     the caller
     * @param now        the current time
     * @param admissible further conditions on a task that has not been offered before (slots)
     * @return the task with the reason it waited, or {@code null}
     */
    Waiting next(Caller caller, long now, Predicate<Step> admissible) {
        for (var entry : waiting) {
            if (eligible(entry, caller, now) && (entry.returned() || admissible.test(entry.step()))) {
                waiting.remove(entry);
                return entry;
            }
        }
        return null;
    }

    private static boolean eligible(Waiting entry, Caller caller, long now) {
        var offer = entry.step().offer();
        if (offer.role() != null && !offer.role().equals(caller.role())) {
            return false;
        }
        if (entry.returned()) {
            return true;
        }
        if (now < offer.releaseAtMillis()) {
            return false;
        }
        return offer.offerTo() == null || offer.offerTo().equals(caller.worker())
                || now >= offer.releaseAtMillis() + offer.fallbackAfterMillis();
    }

    /**
     * @param now the current time
     * @return the tasks that became visible since the last call
     */
    List<String> newlyDue(long now) {
        return waiting.stream().map(Waiting::step)
                .filter(step -> now >= step.offer().releaseAtMillis() && announced.add(step.taskId()))
                .map(Step::taskId).toList();
    }

    /**
     * @param now the current time
     * @return what the driver sees: waiting tasks, leases, and the visible work per role
     */
    JsonObject state(long now) {
        var tasks = new JsonArray();
        var work = new JsonObject();
        for (var entry : waiting) {
            var offer = entry.step().offer();
            var due = entry.returned() || now >= offer.releaseAtMillis();
            tasks.add(new JsonObject().put(TASK_ID, entry.step().taskId()).put("role", offer.role())
                    .put("reason", entry.reason()).put("due", due));
            if (due) {
                var role = offer.role() == null ? "any" : offer.role();
                work.put(role, work.getInteger(role, 0) + 1);
            }
        }
        var held = new JsonArray();
        for (var lease : leases.values()) {
            held.add(new JsonObject().put(TASK_ID, lease.step().taskId()).put("worker", lease.holder().worker())
                    .put("generation", lease.holder().generation()).put("acked", lease.acked())
                    .put("age_ms", now - lease.offeredAt()).put("deliveries", lease.deliveries()));
        }
        return new JsonObject().put("waiting", tasks).put("leases", held).put("work", work)
                .put("submitted", submitted.size()).put("finished", drained());
    }
}
