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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Evaluates the gate-16 pass criteria (doc/roadmap/technical_linux.adoc, L11) of one fault cell from the stub's
 * event log and the supervisor log. Pure: the same evaluation runs on the archived Part A logs.
 * <ul>
 * <li>every task is submitted exactly once;</li>
 * <li>every lost worker is detected within the deadlines: an exit within about 1 s, a stall by silence within
 * bounded wait + grace and never earlier;</li>
 * <li>the lease is released at the fence;</li>
 * <li>every late call of a replaced generation is refused;</li>
 * <li>no stalled-but-alive worker is replaced before its deadline.</li>
 * </ul>
 */
final class TrialEvaluator {

    private static final Set<String> NOT_A_DETECTION = Set.of("shutdown", "trial_setup");
    private static final String RELEASED = "released";

    /** A pass criterion of gate 16, plus the recovery of a relay kill. */
    enum Criterion {
        EXACTLY_ONCE, DETECTION, LEASE_RELEASED, LATE_CALLS_REFUSED, NO_EARLY_REPLACEMENT, RECOVERY;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The deadlines of the evaluation.
     *
     * @param silenceLimitMs     bounded wait plus silence grace
     * @param exitDetectMaxMs    the longest accepted time from a kill to its detection ("about 1 s")
     * @param silenceToleranceMs how much later than the silence limit a stall may be detected
     */
    record Limits(long silenceLimitMs, long exitDetectMaxMs, long silenceToleranceMs) {
    }

    /**
     * One evaluated trial.
     *
     * @param fault           the fault
     * @param worker          the faulted worker
     * @param generation      its generation
     * @param taskId          the task the fault took along, may be {@code null}
     * @param injectedMs      when the fault was injected
     * @param detectMs        when the loss was detected, may be {@code null}
     * @param detectReason    {@code exit} or {@code silence}, may be {@code null}
     * @param detectLatencyMs kill (or exit) to detection, or the silence at detection, may be {@code null}
     * @param released        the tasks released at the fence
     * @param submits         how often the task was submitted
     * @param submittedMs     the first submit of the task, may be {@code null}
     * @param submittedBy     {@code worker/generation} of that submit, may be {@code null}
     * @param recoveredMs     the next wait of the worker after a relay kill, may be {@code null}
     * @param lateRefused     calls of the replaced generation refused after its fence
     * @param failures        the violated criteria with a reason each
     */
    record Trial(Fault fault, String worker, int generation, String taskId, long injectedMs, Long detectMs,
    String detectReason, Long detectLatencyMs, List<String> released, int submits, Long submittedMs,
    String submittedBy, Long recoveredMs, int lateRefused, Map<Criterion, String> failures) {

        boolean pass() {
            return failures.isEmpty();
        }

        Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("fault", fault.id());
            map.put("worker", worker);
            map.put("generation", generation);
            map.put("task_id", taskId);
            map.put("injected_ms", injectedMs);
            map.put("detect_ms", detectMs);
            map.put("detect_reason", detectReason);
            map.put("detect_latency_ms", detectLatencyMs);
            map.put(RELEASED, released);
            map.put("submits", submits);
            map.put("submitted_ms", submittedMs);
            map.put("submitted_by", submittedBy);
            map.put("recovered_ms", recoveredMs);
            map.put("late_refused", lateRefused);
            var reasons = new LinkedHashMap<String, String>();
            failures.forEach((criterion, reason) -> reasons.put(criterion.key(), reason));
            map.put("failures", reasons);
            map.put("pass", pass());
            return map;
        }
    }

    /**
     * The evaluation of one cell.
     *
     * @param trials   the trials
     * @param criteria per criterion whether it held
     * @param global   violations found outside the trials (duplicate submits, late accepted calls, early
     *                 silence detections)
     */
    record Evaluation(List<Trial> trials, Map<Criterion, Boolean> criteria, List<String> global) {

        boolean pass() {
            return !trials.isEmpty() && criteria.values().stream().allMatch(Boolean::booleanValue);
        }

        Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("trials", trials.size());
            var held = new LinkedHashMap<String, Boolean>();
            criteria.forEach((criterion, ok) -> held.put(criterion.key(), ok));
            map.put("criteria", held);
            var latencies = trials.stream().map(Trial::detectLatencyMs).filter(Objects::nonNull)
                    .sorted().toList();
            map.put("detect_latency_ms", latencies);
            map.put("detect_latency_max_ms", latencies.isEmpty() ? null : latencies.getLast());
            map.put("late_refused", trials.stream().mapToInt(Trial::lateRefused).sum());
            map.put("global_violations", global);
            map.put("per_trial", trials.stream().map(Trial::toMap).toList());
            map.put("pass", pass());
            return map;
        }
    }

    private final List<StubEvent> events;
    private final List<StubEvent> supervisor;
    private final Limits limits;

    private TrialEvaluator(List<StubEvent> events, List<StubEvent> supervisor, Limits limits) {
        this.events = events.stream().sorted(Comparator.comparingLong(StubEvent::tMs)).toList();
        this.supervisor = supervisor.stream().sorted(Comparator.comparingLong(StubEvent::tMs)).toList();
        this.limits = limits;
    }

    /**
     * @param events     the stub's event log
     * @param supervisor the supervisor log
     * @param limits     the deadlines
     * @return the evaluation
     */
    static Evaluation evaluate(List<StubEvent> events, List<StubEvent> supervisor, Limits limits) {
        return new TrialEvaluator(events, supervisor, limits).run();
    }

    private Evaluation run() {
        var trials = new ArrayList<Trial>();
        for (var inject : supervisor) {
            if ("inject".equals(inject.name())) {
                trials.add(trial(inject));
            }
        }
        var global = new ArrayList<String>();
        var violated = new EnumMap<Criterion, Boolean>(Criterion.class);
        duplicates(global, violated);
        lateAccepted(global, violated);
        earlySilence(global, violated);
        var criteria = new EnumMap<Criterion, Boolean>(Criterion.class);
        for (var criterion : Criterion.values()) {
            var ok = !violated.getOrDefault(criterion, false)
                    && trials.stream().noneMatch(trial -> trial.failures().containsKey(criterion));
            criteria.put(criterion, ok);
        }
        return new Evaluation(List.copyOf(trials), criteria, List.copyOf(global));
    }

    private Trial trial(StubEvent inject) {
        var fault = Fault.of(inject.text("fault"));
        var worker = inject.worker();
        int generation = inject.generation();
        long injected = Optional.ofNullable(inject.number("injected_ms")).orElse(inject.tMs());
        var taskId = inject.taskId();
        if (taskId == null && fault == Fault.TURN_END) {
            taskId = first(events, "offer", worker, generation, injected).map(StubEvent::taskId).orElse(null);
        }
        var failures = new EnumMap<Criterion, String>(Criterion.class);
        var detect = supervisor.stream().filter(record -> "detect".equals(record.name())
                && record.belongsTo(worker, generation) && record.tMs() >= injected
                && !NOT_A_DETECTION.contains(record.text("reason"))).findFirst();
        var fence = supervisor.stream().filter(record -> "fence".equals(record.name())
                && record.belongsTo(worker, generation) && record.tMs() >= injected).findFirst();
        var fencedAt = first(events, "fenced", worker, generation, injected).map(StubEvent::tMs)
                .or(() -> fence.map(StubEvent::tMs));
        var released = released(fence, worker, generation, injected);
        var task = taskId;
        var submits = task == null ? List.<StubEvent>of() : events.stream()
                .filter(event -> "submit".equals(event.name()) && task.equals(event.taskId())).toList();
        var submitCount = submits.size();
        var firstSubmit = submitCount == 0 ? null : submits.getFirst();
        Long latency = null;
        if (detect.isPresent()) {
            latency = latency(fault, detect.get(), injected);
        }
        Long recovered = null;
        if (fault == Fault.RELAY_KILL) {
            recovered = events.stream().filter(event -> "wait_start".equals(event.name())
                    && worker.equals(event.worker()) && event.tMs() > injected).map(StubEvent::tMs).findFirst()
                    .orElse(null);
        }
        switch (fault) {
            case KILL_OFFER, KILL_EXEC, TURN_END -> {
                lost(detect, "exit", latency, failures);
                if (latency != null && latency > limits.exitDetectMaxMs()) {
                    failures.put(Criterion.DETECTION, "exit detected after " + latency + " ms");
                }
            }
            case STOP_LONG -> {
                lost(detect, "silence", latency, failures);
                detect.ifPresent(found -> silence(found, failures));
            }
            case STOP_SHORT -> {
                var stop = Optional.ofNullable(inject.number("stop_s")).orElse(0L) * 1000;
                if (detect.isPresent() && detect.get().tMs() <= injected + stop + limits.silenceLimitMs()) {
                    failures.put(Criterion.NO_EARLY_REPLACEMENT, "a stall of " + stop + " ms was replaced ("
                            + detect.get().text("reason") + ")");
                }
            }
            case RELAY_KILL -> {
                if (recovered == null && detect.isEmpty()) {
                    failures.put(Criterion.RECOVERY, "neither a new wait nor a detected exit after the relay kill");
                }
            }
        }
        if (taskId != null && fault.losesTask()) {
            var submittedBeforeFence = firstSubmit != null && fencedAt.isPresent()
                    && firstSubmit.tMs() < fencedAt.get();
            if (!submittedBeforeFence && !released.contains(taskId)) {
                failures.put(Criterion.LEASE_RELEASED, "task " + taskId + " not released at the fence " + released);
            }
        }
        if (taskId != null && submitCount != 1) {
            failures.put(Criterion.EXACTLY_ONCE, "task " + taskId + " submitted " + submitCount + " times");
        }
        var lateRefused = fencedAt.map(at -> (int) events.stream().filter(event -> "stale_refused".equals(event.name())
                && worker.equals(event.worker()) && event.generation() != null && event.generation() <= generation
                && event.tMs() >= at).count()).orElse(0);
        return new Trial(fault, worker, generation, taskId, injected, detect.map(StubEvent::tMs).orElse(null),
                detect.map(found -> found.text("reason")).orElse(null), latency, released, submitCount,
                firstSubmit == null ? null : firstSubmit.tMs(),
                firstSubmit == null ? null : firstSubmit.worker() + "/" + firstSubmit.generation(), recovered,
                lateRefused, failures);
    }

    private Long latency(Fault fault, StubEvent detect, long injected) {
        if ("silence".equals(detect.text("reason"))) {
            return detect.number("silent_ms");
        }
        if (fault == Fault.TURN_END || fault == Fault.RELAY_KILL) {
            var exit = detect.number("exit_ms");
            return exit == null ? null : detect.tMs() - exit;
        }
        return detect.tMs() - injected;
    }

    private static void lost(Optional<StubEvent> detect, String reason, Long latency, Map<Criterion, String> failures) {
        if (detect.isEmpty()) {
            failures.put(Criterion.DETECTION, "the lost worker was never detected");
        } else if (!reason.equals(detect.get().text("reason"))) {
            failures.put(Criterion.DETECTION, "detected by " + detect.get().text("reason") + ", expected " + reason
                    + (latency == null ? "" : " (" + latency + " ms)"));
        }
    }

    private void silence(StubEvent detect, Map<Criterion, String> failures) {
        var silent = detect.number("silent_ms");
        var last = detect.number("last_event_ms");
        if (silent != null && silent < limits.silenceLimitMs()) {
            failures.put(Criterion.NO_EARLY_REPLACEMENT, "replaced after " + silent + " ms of silence, limit "
                    + limits.silenceLimitMs());
        }
        if (last != null && detect.tMs() - last > limits.silenceLimitMs() + limits.silenceToleranceMs()) {
            failures.put(Criterion.DETECTION, "stall detected after " + (detect.tMs() - last) + " ms, limit "
                    + limits.silenceLimitMs());
        }
    }

    private List<String> released(Optional<StubEvent> fence, String worker, int generation, long injected) {
        var released = new ArrayList<String>();
        fence.map(record -> record.raw().path(RELEASED)).ifPresent(array -> array.forEach(id -> released.add(id.asText())));
        first(events, "fenced", worker, generation, injected).ifPresent(event -> event.raw().path(RELEASED)
                .forEach(id -> {
                    if (!released.contains(id.asText())) {
                        released.add(id.asText());
                    }
                }));
        return List.copyOf(released);
    }

    private static Optional<StubEvent> first(List<StubEvent> log, String name, String worker, int generation,
            long from) {
        return log.stream().filter(event -> name.equals(event.name()) && event.belongsTo(worker, generation)
                && event.tMs() >= from).findFirst();
    }

    private void duplicates(List<String> global, Map<Criterion, Boolean> violated) {
        var counts = new HashMap<String, Integer>();
        events.stream().filter(event -> "submit".equals(event.name()) && event.taskId() != null)
                .forEach(event -> counts.merge(event.taskId(), 1, Integer::sum));
        counts.forEach((task, count) -> {
            if (count > 1) {
                global.add("task " + task + " submitted " + count + " times");
                violated.put(Criterion.EXACTLY_ONCE, true);
            }
        });
    }

    private void lateAccepted(List<String> global, Map<Criterion, Boolean> violated) {
        for (var fence : supervisor) {
            if (!"fence".equals(fence.name()) || fence.worker() == null || fence.generation() == null) {
                continue;
            }
            var worker = fence.worker();
            int generation = fence.generation();
            var at = first(events, "fenced", worker, generation, fence.tMs() - 5_000).map(StubEvent::tMs)
                    .orElse(fence.tMs());
            events.stream().filter(event -> StubEvent.ACCEPTED.contains(event.name()) && worker.equals(event.worker())
                    && event.generation() != null && event.generation() <= generation && event.tMs() > at)
                    .forEach(event -> {
                        global.add("late " + event.name() + " of " + worker + "/" + event.generation() + " accepted at "
                                + event.tMs() + " after the fence at " + at);
                        violated.put(Criterion.LATE_CALLS_REFUSED, true);
                    });
        }
    }

    private void earlySilence(List<String> global, Map<Criterion, Boolean> violated) {
        for (var detect : supervisor) {
            var silent = detect.number("silent_ms");
            if ("detect".equals(detect.name()) && "silence".equals(detect.text("reason")) && silent != null
                    && silent < limits.silenceLimitMs()) {
                global.add("worker " + detect.worker() + "/" + detect.generation() + " replaced after " + silent
                        + " ms of silence");
                violated.put(Criterion.NO_EARLY_REPLACEMENT, true);
            }
        }
    }
}
