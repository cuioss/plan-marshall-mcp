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

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Decides when the next trial of a fault cell starts and when the running one has settled; pure, so the
 * schedule is testable on recorded event logs. One trial runs at a time, and the next starts only after a
 * cooldown.
 * <ul>
 * <li>A trial is triggered by the fault's stub event ({@link Fault#triggerEvent()}) of an eligible worker
 * generation: a fresh offer (first delivery) for {@code kill-offer}, an acknowledgement for {@code kill-exec} and
 * the stops, a held wait for {@code relay-kill}. A task is faulted at most once. {@code turn-end} starts by itself
 * ({@link #turnEndDue(long)}).</li>
 * <li>It settles when its task is submitted (by anyone), for {@code relay-kill} when the worker waits again, and
 * otherwise after the trial timeout.</li>
 * </ul>
 */
final class FaultScheduler {

    /**
     * A decided injection.
     *
     * @param fault      the fault
     * @param worker     the worker
     * @param generation its generation
     * @param taskId     the task it holds, may be {@code null}
     * @param triggerMs  the time of the triggering event
     */
    record Injection(Fault fault, String worker, int generation, String taskId, long triggerMs) {
    }

    private final Fault fault;
    private final int trials;
    private final long cooldownMs;
    private final long trialTimeoutMs;
    private final Set<String> faultedTasks = new HashSet<>();
    private int started;
    private Injection active;
    private long activeSince;
    private String activeTask;
    private long idleSince;

    /**
     * @param fault          the fault of the cell
     * @param trials         how many trials
     * @param cooldownMs     the pause between a settled trial and the next
     * @param trialTimeoutMs the bound of one trial
     * @param startMs        the start of the cell
     */
    FaultScheduler(Fault fault, int trials, long cooldownMs, long trialTimeoutMs, long startMs) {
        this.fault = fault;
        this.trials = trials;
        this.cooldownMs = cooldownMs;
        this.trialTimeoutMs = trialTimeoutMs;
        this.idleSince = startMs;
    }

    /**
     * Offers a stub event; answers the injection it triggers.
     *
     * @param event    the event
     * @param nowMs    the current time
     * @param eligible whether a worker generation may be faulted now (alive, current, not stalled)
     * @return the injection to perform at once, or empty
     */
    Optional<Injection> onEvent(StubEvent event, long nowMs, BiPredicate<String, Integer> eligible) {
        if (active != null) {
            track(event);
            return Optional.empty();
        }
        if (done() || fault == Fault.TURN_END || nowMs - idleSince < cooldownMs
                || !event.name().equals(fault.triggerEvent()) || event.worker() == null || event.generation() == null
                || !eligible.test(event.worker(), event.generation())) {
            return Optional.empty();
        }
        if (fault == Fault.RELAY_KILL && !"held".equals(event.text("status"))) {
            return Optional.empty();
        }
        if (fault == Fault.KILL_OFFER && event.number("deliveries") != null && event.number("deliveries") != 1) {
            return Optional.empty();
        }
        if (event.taskId() != null && !faultedTasks.add(event.taskId())) {
            return Optional.empty();
        }
        return Optional.of(new Injection(fault, event.worker(), event.generation(), event.taskId(), event.tMs()));
    }

    /**
     * @param nowMs the current time
     * @return whether the next turn-end trial should start now
     */
    boolean turnEndDue(long nowMs) {
        return fault == Fault.TURN_END && active == null && !done() && nowMs - idleSince >= cooldownMs;
    }

    /**
     * Records that an injection was performed; the trial is running from now on.
     *
     * @param injection the injection
     * @param nowMs     the time of the injection
     */
    void started(Injection injection, long nowMs) {
        active = injection;
        activeSince = nowMs;
        activeTask = injection.taskId();
        started++;
    }

    private void track(StubEvent event) {
        if (activeTask == null && active.fault() == Fault.TURN_END && "offer".equals(event.name())
                && event.belongsTo(active.worker(), active.generation())) {
            activeTask = event.taskId();
            faultedTasks.add(activeTask);
        }
        var settled = switch (active.fault()) {
            case RELAY_KILL -> "wait_start".equals(event.name()) && active.worker().equals(event.worker())
                    && event.tMs() > activeSince;
            default -> "submit".equals(event.name()) && activeTask != null && activeTask.equals(event.taskId());
        };
        if (settled) {
            settle(event.tMs());
        }
    }

    /**
     * Ends the running trial when it timed out.
     *
     * @param nowMs the current time
     * @return whether the trial timed out now
     */
    boolean timeout(long nowMs) {
        if (active != null && nowMs - activeSince > trialTimeoutMs) {
            settle(nowMs);
            return true;
        }
        return false;
    }

    private void settle(long atMs) {
        active = null;
        activeTask = null;
        idleSince = atMs;
    }

    /** @return the running trial, or {@code null} */
    Injection active() {
        return active;
    }

    /** @return the number of trials started */
    int started() {
        return started;
    }

    /** @return whether every trial was started and the last one settled */
    boolean done() {
        return started >= trials && active == null;
    }
}
