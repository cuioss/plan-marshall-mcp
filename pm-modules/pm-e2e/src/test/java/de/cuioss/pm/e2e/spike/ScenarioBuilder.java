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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The stub scenarios of the three items, built from the 24 decision tasks of the Part A E2 scenario
 * ({@code spike/tasks.json}).
 */
final class ScenarioBuilder {

    private static final List<JsonNode> TEMPLATES = templates();

    /**
     * One task of a gate-13 scenario.
     *
     * @param id      the task id
     * @param offerTo the worker it is reserved for
     * @param chars   the size of its facts in characters, {@code 0} for the template's own
     */
    record SizedTask(String id, String offerTo, int chars) {
    }

    private ScenarioBuilder() {
    }

    private static List<JsonNode> templates() {
        try (var in = ScenarioBuilder.class.getResourceAsStream("/spike/tasks.json")) {
            var list = new ArrayList<JsonNode>();
            StubEvent.JSON.readTree(in).forEach(list::add);
            return List.copyOf(list);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the number of distinct task templates */
    static int templateCount() {
        return TEMPLATES.size();
    }

    private static ObjectNode supervised(int waitSeconds) {
        var root = StubEvent.JSON.createObjectNode();
        root.put("supervised", true);
        root.put("control", false);
        root.put("links", true);
        root.put("next_hints", false);
        root.put("wait_seconds", waitSeconds);
        root.put("progress_seconds", 0);
        root.put("lease_seconds", 900);
        root.put("slots", 2);
        root.put("ack", "explicit");
        root.put("ack_deadline_seconds", 60);
        return root;
    }

    /**
     * The E2 scenario of Part A: one task every ten seconds, the first after five.
     *
     * @param waitSeconds the bounded wait
     * @param tasks       the number of tasks
     * @return the scenario
     */
    static ObjectNode faultMatrix(int waitSeconds, int tasks) {
        var root = supervised(waitSeconds);
        var steps = root.putArray("steps");
        for (var i = 0; i < tasks; i++) {
            var template = TEMPLATES.get(i % TEMPLATES.size());
            var step = steps.addObject();
            step.put("kind", "task");
            step.put("id", String.format(Locale.ROOT, "f%03d-%s-%02d", i, kind(template), i % TEMPLATES.size() + 1));
            step.put("writer", false);
            step.put("role", "worker");
            step.put("release_at_seconds", 5 + 10 * i);
            step.set("task", template.deepCopy());
        }
        return root;
    }

    /**
     * The gate-13 scenario: one task per worker, reserved for it, its facts padded to a size.
     *
     * @param waitSeconds the bounded wait
     * @param tasks       the tasks
     * @return the scenario
     */
    static ObjectNode sized(int waitSeconds, List<SizedTask> tasks) {
        var root = supervised(waitSeconds);
        var steps = root.putArray("steps");
        for (var i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            var body = (ObjectNode) TEMPLATES.get(i % TEMPLATES.size()).deepCopy();
            if (task.chars() > 0) {
                body.put("facts", pad(body.path("facts").asText(), task.chars()));
            }
            var step = steps.addObject();
            step.put("kind", "task");
            step.put("id", task.id());
            step.put("role", "worker");
            step.put("offer_to", task.offerTo());
            step.put("fallback_after_seconds", 86_400);
            step.set("task", body);
        }
        return root;
    }

    /**
     * The gate-9 scenario: outside the supervised protocol, the first wait blocks for {@code holdSeconds}.
     *
     * @param holdSeconds     how long the wait holds
     * @param progressSeconds the progress interval, {@code 0} for none
     * @return the scenario
     */
    static ObjectNode blockingWait(int holdSeconds, int progressSeconds) {
        var root = StubEvent.JSON.createObjectNode();
        root.put("wait_seconds", holdSeconds);
        root.put("progress_seconds", progressSeconds);
        root.put("next_hints", false);
        var steps = root.putArray("steps");
        steps.addObject().put("kind", "wait").put("seconds", holdSeconds).put("repeat", 4);
        steps.addObject().put("kind", "done");
        return root;
    }

    /**
     * Pads facts to a size with neutral log lines that do not change the decision.
     *
     * @param facts the facts
     * @param chars the size
     * @return the padded facts
     */
    static String pad(String facts, int chars) {
        var text = new StringBuilder(facts).append(" Unrelated build log follows.");
        for (var line = 1; text.length() < chars; line++) {
            text.append(String.format(Locale.ROOT, " [%05d] step %d of the unrelated module finished without findings.",
                    line, line));
        }
        return text.substring(0, Math.max(chars, facts.length()));
    }

    /**
     * @param scenario the scenario
     * @param file     the target file
     * @return the file
     */
    static Path write(ObjectNode scenario, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, StubEvent.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(scenario));
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String kind(JsonNode template) {
        var kind = template.path("kind").asText("task");
        return kind.length() <= 3 ? kind : kind.substring(0, 3);
    }
}
