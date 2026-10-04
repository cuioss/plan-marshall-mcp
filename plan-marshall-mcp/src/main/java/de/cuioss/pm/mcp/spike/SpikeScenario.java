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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The script of one spike run: what the wait tool answers, in which order, and after how long.
 * <p>
 * JSON form (durations in seconds, fractions allowed):
 * {@code {"wait_seconds": 30, "progress_seconds": 5, "lease_seconds": 120, "slots": 2,
 * "add_tool_after_deliveries": -1, "next_hints": true, "steps": [{"kind": "wait", "repeat": 240}, {"kind": "task", "id": "t1",
 * "writer": false, "delay_seconds": 0, "task": {}}, {"kind": "done"}]}}.
 * <p>
 * The supervised task protocol of the evaluation is switched on per scenario with
 * {@code "supervised": true}; its keys are {@code ack} ({@code none}, {@code explicit}, {@code implicit}),
 * {@code ack_deadline_seconds}, {@code links}, {@code consult}, {@code skills_dir} and {@code control}, and per
 * task {@code role}, {@code release_at_seconds}, {@code offer_to}, {@code fallback_after_seconds} and
 * {@code skills}. A scenario without them behaves as before.
 *
 * @param waitMillis             how long a wait step blocks unless the step names its own duration
 * @param progressMillis         interval of progress notifications during a blocking call, {@code 0} for none
 * @param leaseMillis            how long a delivered task stays bound to its connection without a submit
 * @param slots                  how many tasks may be delivered and not yet submitted at the same time
 * @param addToolAfterDeliveries number of deliveries after which an additional tool is registered, negative
 *                               for never
 * @param nextHints              whether every answer names the next call, as the fresh links of a real wait
 *                               answer do
 * @param protocol               the switches of the supervised task protocol
 * @param steps                  the steps in delivery order, repeats expanded
 */
record SpikeScenario(long waitMillis, long progressMillis, long leaseMillis, int slots, int addToolAfterDeliveries,
boolean nextHints, Protocol protocol, List<Step> steps) {

    private static final double MILLIS_PER_SECOND = 1000.0;

    /** Kind of a scenario step. */
    enum Kind {
        /** The wait call blocks and answers "wait again". */
        WAIT,
        /** The wait call delivers a task. */
        TASK,
        /** The wait call reports the end of the run. */
        DONE
    }

    /** How a worker confirms an offered task. */
    enum Ack {
        /** No acknowledgement: the submit alone ends the lease. */
        NONE,
        /** The worker calls the acknowledge tool. */
        EXPLICIT,
        /** The first call that follows the task's link counts as the acknowledgement. */
        IMPLICIT
    }

    /**
     * The switches of the supervised task protocol.
     *
     * @param supervised        whether waits are held until a task is due and calls carry generation and role
     * @param ack               how a worker confirms an offered task
     * @param ackDeadlineMillis how long an offer may stay unconfirmed before its lease is released
     * @param links             whether an offer lists its follow-up calls as tool names with arguments
     * @param consult           whether a worker may ask another role through the server
     * @param skillsDir         directory of the skill catalogue, {@code null} for none
     * @param control           whether the tools of the driver are registered
     */
    record Protocol(boolean supervised, Ack ack, long ackDeadlineMillis, boolean links, boolean consult,
    String skillsDir, boolean control) {
    }

    /**
     * To whom and when a task is offered under the supervised protocol.
     *
     * @param role                the role whose workers pull the task, {@code null} for any
     * @param releaseAtMillis     time since the start of the run before which the task is invisible
     * @param offerTo             the worker the task is offered to first, {@code null} for any
     * @param fallbackAfterMillis time after the release from which any worker of the role may take the task
     * @param skills              URIs of the skills the task requires
     */
    record Offer(String role, long releaseAtMillis, String offerTo, long fallbackAfterMillis, List<String> skills) {

        /** An offer to any worker at once, without skills. */
        static final Offer ANY = new Offer(null, 0, null, 0, List.of());

        Offer {
            skills = List.copyOf(skills);
        }
    }

    /**
     * One step of the scenario.
     *
     * @param kind     the kind
     * @param millis   how long the wait call blocks before it answers
     * @param padBytes size of the filler text returned with a "wait again" answer
     * @param taskId   identifier of the task, {@code null} for other kinds
     * @param writer   whether the task counts as writer work (at most one delivered at a time)
     * @param task     the task body handed to the model, {@code null} for other kinds
     * @param offer    to whom and when the task is offered under the supervised protocol
     */
    record Step(Kind kind, long millis, int padBytes, String taskId, boolean writer, JsonObject task, Offer offer) {
    }

    SpikeScenario {
        steps = List.copyOf(steps);
    }

    /**
     * Reads a scenario file.
     *
     * @param file the JSON file
     * @return the scenario
     * @throws IOException when the file cannot be read
     */
    static SpikeScenario load(Path file) throws IOException {
        return parse(new JsonObject(Files.readString(file)));
    }

    /**
     * Builds a scenario from its JSON form.
     *
     * @param json the JSON form
     * @return the scenario
     */
    static SpikeScenario parse(JsonObject json) {
        var waitMillis = millis(json.getDouble("wait_seconds", 30.0));
        var steps = new ArrayList<Step>();
        for (var element : json.getJsonArray("steps")) {
            var step = (JsonObject) element;
            var kind = Kind.valueOf(step.getString("kind").toUpperCase(Locale.ROOT));
            switch (kind) {
                case WAIT -> {
                    var wait = new Step(kind, millis(step.getDouble("seconds", waitMillis / MILLIS_PER_SECOND)),
                            step.getInteger("pad_bytes", 0), null, false, null, Offer.ANY);
                    for (var i = 0; i < step.getInteger("repeat", 1); i++) {
                        steps.add(wait);
                    }
                }
                case TASK -> steps.add(new Step(kind, millis(step.getDouble("delay_seconds", 0.0)), 0,
                        step.getString("id"), step.getBoolean("writer", false),
                        step.getJsonObject("task", new JsonObject()), offer(step)));
                case DONE -> steps.add(new Step(kind, 0, 0, null, false, null, Offer.ANY));
            }
        }
        return new SpikeScenario(waitMillis, millis(json.getDouble("progress_seconds", 0.0)),
                millis(json.getDouble("lease_seconds", 120.0)), json.getInteger("slots", 2),
                json.getInteger("add_tool_after_deliveries", -1), json.getBoolean("next_hints", true),
                protocol(json), steps);
    }

    private static Protocol protocol(JsonObject json) {
        return new Protocol(json.getBoolean("supervised", false),
                Ack.valueOf(json.getString("ack", "none").toUpperCase(Locale.ROOT)),
                millis(json.getDouble("ack_deadline_seconds", 30.0)), json.getBoolean("links", false),
                json.getBoolean("consult", false), json.getString("skills_dir"), json.getBoolean("control", false));
    }

    private static Offer offer(JsonObject step) {
        var skills = new ArrayList<String>();
        for (var uri : step.getJsonArray("skills", new JsonArray())) {
            skills.add((String) uri);
        }
        return new Offer(step.getString("role"), millis(step.getDouble("release_at_seconds", 0.0)),
                step.getString("offer_to"), millis(step.getDouble("fallback_after_seconds", 0.0)), skills);
    }

    private static long millis(double seconds) {
        return Math.round(seconds * MILLIS_PER_SECOND);
    }
}
