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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The supervisor and fault injector of one gate-16 cell, standing in for the job runtime as in Part A, but
 * against the real relay and daemon: it keeps {@code workers} worker generations running, injects the fault at
 * the scenario events ({@link FaultScheduler}), detects a lost generation by its exit or by silence in the stub's
 * event log beyond bounded wait + grace, revokes its job token (which fences it in the stub and releases its
 * leases), and starts the next generation with a fresh token.
 * <p>
 * Its log ({@code supervisor.jsonl}) has the record shapes of Part A: {@code spawn}, {@code inject},
 * {@code detect}, {@code fence_sent}, {@code fence}, {@code term}, plus {@code cont} and {@code reap}.
 */
final class Supervisor {

    private static final long TICK_MS = 100;
    private static final long RESPAWN_DELAY_MS = 1_000;
    private static final long COOLDOWN_MS = 5_000;
    private static final long LATE_WINDOW_MS = 90_000;
    private static final int START_FAILURE_LIMIT = 3;

    private final SpikeSettings settings;
    private final SpikeDaemon daemon;
    private final WorkerLauncher launcher;
    private final EventTail tail;
    private final Writer logWriter;
    private final List<StubEvent> records = new ArrayList<>();
    private final Map<String, Worker> current = new LinkedHashMap<>();
    private final List<Worker> all = new ArrayList<>();
    private final Map<String, Long> lastActivity = new HashMap<>();
    private final Map<Worker, Long> stopped = new HashMap<>();
    private final Map<Worker, Long> reapAt = new HashMap<>();
    private final Map<String, Long> respawnAt = new HashMap<>();
    private final Map<String, Integer> generations = new HashMap<>();
    private int startFailures;
    private int turnEndRotation;
    private String aborted;

    /**
     * @param settings the flags
     * @param daemon   the daemon of this cell
     * @param launcher the worker launcher of this cell
     * @param tail     the stub's event log
     * @param log      the supervisor log file
     * @throws IOException if the log cannot be opened
     */
    Supervisor(SpikeSettings settings, SpikeDaemon daemon, WorkerLauncher launcher, EventTail tail, Path log)
            throws IOException {
        this.settings = settings;
        this.daemon = daemon;
        this.launcher = launcher;
        this.tail = tail;
        this.logWriter = Files.newBufferedWriter(log, StandardCharsets.UTF_8);
    }

    /**
     * Runs one fault cell to its last settled trial or its deadline.
     *
     * @param fault the fault
     * @return the reason the cell was aborted, or {@code null}
     * @throws IOException if a worker cannot be started
     */
    String run(Fault fault) throws IOException {
        var start = System.currentTimeMillis();
        var deadline = start + settings.cellDeadlineSeconds() * 1000L;
        var scheduler = new FaultScheduler(fault, settings.trials(), COOLDOWN_MS,
                settings.trialTimeoutSeconds() * 1000L, start);
        for (var i = 1; i <= settings.workers(); i++) {
            spawn("w" + i, "protocol", Prompts.PROTOCOL);
        }
        try {
            while (!scheduler.done() && aborted == null && System.currentTimeMillis() < deadline) {
                var now = System.currentTimeMillis();
                for (var event : tail.poll()) {
                    if (event.activity() && event.worker() != null && event.generation() != null) {
                        lastActivity.merge(key(event.worker(), event.generation()), event.tMs(), Math::max);
                    }
                    var injection = scheduler.onEvent(event, now, this::eligible);
                    if (injection.isPresent() && inject(injection.get())) {
                        scheduler.started(injection.get(), System.currentTimeMillis());
                    }
                }
                if (scheduler.turnEndDue(now)) {
                    turnEnd(scheduler);
                }
                if (scheduler.timeout(now)) {
                    log("trial_timeout", null, 0, StubEvent.JSON.createObjectNode());
                }
                resume(now);
                exits();
                silence(now);
                reap(now);
                respawn(now);
                sleep(TICK_MS);
            }
            if (aborted == null && !scheduler.done()) {
                aborted = "deadline reached after " + scheduler.started() + " of " + settings.trials() + " trials";
            }
        } finally {
            shutdown();
        }
        return aborted;
    }

    /** @return the supervisor records written */
    List<StubEvent> records() {
        return List.copyOf(records);
    }

    /** @return every worker generation started */
    List<Worker> workers() {
        return List.copyOf(all);
    }

    private boolean eligible(String worker, Integer generation) {
        var running = current.get(worker);
        return running != null && running.generation() == generation && running.alive() && !running.replaced()
                && !stopped.containsKey(running) && "protocol".equals(running.promptKind());
    }

    private void spawn(String worker, String promptKind, String prompt) throws IOException {
        var generation = generations.merge(worker, 1, Integer::sum);
        var started = launcher.launch(worker, generation, promptKind, prompt, WorkerLauncher.PROTOCOL_TOOLS, null,
                null);
        current.put(worker, started);
        all.add(started);
        log("spawn", worker, generation, StubEvent.JSON.createObjectNode().put("role", "worker")
                .put("harness", settings.harness().id()).put("pid", started.pid()).put("prompt", promptKind)
                .put("job_id", started.job().jobId()));
    }

    private boolean inject(FaultScheduler.Injection injection) {
        var worker = current.get(injection.worker());
        var fields = StubEvent.JSON.createObjectNode().put("fault", injection.fault().id())
                .put("task_id", injection.taskId()).put("trigger_ms", injection.triggerMs());
        switch (injection.fault()) {
            case KILL_OFFER, KILL_EXEC -> worker.kill();
            case STOP_SHORT, STOP_LONG -> {
                var seconds = injection.fault() == Fault.STOP_SHORT ? settings.stopShortSeconds()
                        : settings.stopLongSeconds();
                ProcessTree.signal(worker.pid(), "STOP");
                stopped.put(worker, System.currentTimeMillis() + seconds * 1000L);
                fields.put("stop_s", seconds);
            }
            case RELAY_KILL -> {
                var relays = ProcessTree.relays(worker.pid());
                if (relays.isEmpty()) {
                    log("inject_skipped", injection.worker(), injection.generation(),
                            fields.put("reason", "no relay process found"));
                    return false;
                }
                relays.forEach(pid -> ProcessTree.signal(pid, "KILL"));
                var array = fields.putArray("relay_pids");
                relays.forEach(array::add);
            }
            case TURN_END -> throw new IllegalStateException("turn-end is started, not injected");
        }
        fields.put("injected_ms", System.currentTimeMillis());
        log("inject", injection.worker(), injection.generation(), fields);
        return true;
    }

    private void turnEnd(FaultScheduler scheduler) throws IOException {
        var names = new ArrayList<>(current.keySet());
        for (var i = 0; i < names.size(); i++) {
            var name = names.get((turnEndRotation + i) % names.size());
            var worker = current.get(name);
            if (worker == null || !eligible(name, worker.generation())) {
                continue;
            }
            turnEndRotation++;
            detect(worker, "trial_setup", StubEvent.JSON.createObjectNode());
            log("term", name, worker.generation(), StubEvent.JSON.createObjectNode());
            worker.terminate(Duration.ofSeconds(5));
            worker.markHandled();
            fence(worker, "trial_setup");
            spawn(name, "turn_end", Prompts.TURN_END);
            var successor = current.get(name);
            var now = System.currentTimeMillis();
            log("inject", name, successor.generation(), StubEvent.JSON.createObjectNode()
                    .put("fault", Fault.TURN_END.id()).put("injected_ms", now));
            scheduler.started(new FaultScheduler.Injection(Fault.TURN_END, name, successor.generation(), null, now),
                    now);
            return;
        }
    }

    private void resume(long now) {
        for (var entry : List.copyOf(stopped.entrySet())) {
            if (entry.getValue() <= now) {
                ProcessTree.signal(entry.getKey().pid(), "CONT");
                stopped.remove(entry.getKey());
                log("cont", entry.getKey().worker(), entry.getKey().generation(), StubEvent.JSON.createObjectNode());
            }
        }
    }

    private void exits() throws IOException {
        for (var worker : List.copyOf(all)) {
            if (worker.alive() || worker.handled()) {
                continue;
            }
            worker.markHandled();
            worker.closeLog();
            if (worker.replaced()) {
                log("reap", worker.worker(), worker.generation(), StubEvent.JSON.createObjectNode()
                        .put("exit_code", worker.exitCode()).put("exit_ms", worker.exitMs()));
                continue;
            }
            var key = key(worker.worker(), worker.generation());
            var fields = StubEvent.JSON.createObjectNode().put("exit_code", worker.exitCode())
                    .put("exit_ms", worker.exitMs());
            if (lastActivity.containsKey(key)) {
                fields.put("last_event_ms", lastActivity.get(key));
                startFailures = 0;
            } else if (++startFailures >= START_FAILURE_LIMIT) {
                aborted = startFailures + " generations in a row exited before their first call (start failures); "
                        + "last stderr: " + worker.stderr();
            }
            detect(worker, "exit", fields);
            fence(worker, "exit");
            respawnAt.put(worker.worker(), System.currentTimeMillis() + RESPAWN_DELAY_MS);
        }
    }

    private void silence(long now) throws IOException {
        for (var worker : List.copyOf(current.values())) {
            if (!worker.alive() || worker.replaced()) {
                continue;
            }
            var last = lastActivity.getOrDefault(key(worker.worker(), worker.generation()), worker.startedMs());
            if (now - last > settings.silenceLimitMs()) {
                detect(worker, "silence", StubEvent.JSON.createObjectNode().put("last_event_ms", last)
                        .put("silent_ms", now - last));
                fence(worker, "silence");
                reapAt.put(worker, stopped.getOrDefault(worker, now) + LATE_WINDOW_MS);
                respawnAt.put(worker.worker(), now + RESPAWN_DELAY_MS);
            }
        }
    }

    private void reap(long now) {
        for (var entry : List.copyOf(reapAt.entrySet())) {
            if (entry.getValue() <= now && !stopped.containsKey(entry.getKey())) {
                entry.getKey().kill();
                reapAt.remove(entry.getKey());
            }
        }
    }

    private void respawn(long now) throws IOException {
        for (var entry : List.copyOf(respawnAt.entrySet())) {
            if (entry.getValue() <= now && aborted == null) {
                respawnAt.remove(entry.getKey());
                spawn(entry.getKey(), "protocol", Prompts.PROTOCOL);
            }
        }
    }

    private void detect(Worker worker, String reason, ObjectNode fields) {
        log("detect", worker.worker(), worker.generation(), fields.put("reason", reason));
    }

    private void fence(Worker worker, String reason) {
        worker.markReplaced();
        log("fence_sent", worker.worker(), worker.generation(), StubEvent.JSON.createObjectNode());
        var fields = StubEvent.JSON.createObjectNode().put("reason", reason);
        try {
            var answer = daemon.revoke(worker.job().jobId());
            fields.set("released", answer.path("released"));
        } catch (IOException e) {
            fields.put("error", e.getMessage());
        }
        log("fence", worker.worker(), worker.generation(), fields);
    }

    private void shutdown() {
        for (var worker : List.copyOf(stopped.keySet())) {
            ProcessTree.signal(worker.pid(), "CONT");
        }
        stopped.clear();
        for (var worker : List.copyOf(current.values())) {
            if (worker.alive() && !worker.replaced()) {
                detect(worker, "shutdown", StubEvent.JSON.createObjectNode().put("last_event_ms",
                        lastActivity.getOrDefault(key(worker.worker(), worker.generation()), worker.startedMs())));
                log("term", worker.worker(), worker.generation(), StubEvent.JSON.createObjectNode());
                worker.terminate(Duration.ofSeconds(5));
                worker.markHandled();
                fence(worker, "shutdown");
            }
        }
        for (var worker : all) {
            if (worker.alive()) {
                worker.kill();
            }
            worker.closeLog();
        }
        try {
            logWriter.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void log(String event, String worker, int generation, ObjectNode fields) {
        var record = StubEvent.JSON.createObjectNode().put("t_ms", System.currentTimeMillis()).put("event", event);
        if (worker != null) {
            record.put("worker", worker).put("generation", generation);
        }
        record.setAll(fields);
        records.add(StubEvent.of(record));
        try {
            logWriter.write(record.toString());
            logWriter.write('\n');
            logWriter.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String key(String worker, int generation) {
        return worker + "/" + generation;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
