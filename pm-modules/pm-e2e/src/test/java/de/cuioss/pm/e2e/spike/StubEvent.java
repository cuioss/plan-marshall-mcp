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
import java.util.Set;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One line of the stub's event log ({@code events.jsonl}) or of the supervisor log, which share the shape
 * {@code {"t_ms":…,"event":…,…}}.
 *
 * @param tMs        wall-clock time in milliseconds
 * @param name       the event name
 * @param worker     the worker ({@code connection} of a stub event, {@code worker} of a supervisor record), may be
 *                   {@code null}
 * @param generation the generation, may be {@code null}
 * @param taskId     the task, may be {@code null}
 * @param raw        the whole record
 */
record StubEvent(long tMs, String name, String worker, Integer generation, String taskId, JsonNode raw) {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Stub events a worker generation causes by its own calls; they count against its silence. */
    static final Set<String> ACTIVITY = Set.of("wait_start", "ack", "submit", "submit_refused", "stale_refused",
            "ack_refused", "task_refused", "consult_refused", "consult_open", "skill_read", "info_called");

    /** Stub events that only an accepted call of a generation produces. */
    static final Set<String> ACCEPTED = Set.of("offer", "ack", "submit", "consult_open");

    /**
     * @param node one record
     * @return the event
     */
    static StubEvent of(JsonNode node) {
        var worker = text(node, "connection");
        if (worker == null) {
            worker = text(node, "worker");
        }
        var generation = node.hasNonNull("generation") ? Integer.valueOf(node.get("generation").asInt()) : null;
        return new StubEvent(node.path("t_ms").asLong(), text(node, "event"), worker, generation,
                text(node, "task_id"), node);
    }

    /**
     * @param line one JSON line
     * @return the event
     */
    static StubEvent parse(String line) {
        try {
            return of(JSON.readTree(line));
        } catch (IOException e) {
            throw new UncheckedIOException("not a JSON record: " + line, e);
        }
    }

    /**
     * @param file a JSON-lines file
     * @return its records; an absent file has none
     */
    static List<StubEvent> readAll(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            var events = new ArrayList<StubEvent>();
            for (var line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    events.add(parse(line));
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param otherWorker     a worker
     * @param otherGeneration a generation
     * @return whether this event belongs to that worker generation
     */
    boolean belongsTo(String otherWorker, int otherGeneration) {
        return otherWorker.equals(worker) && generation != null && generation == otherGeneration;
    }

    /** @return whether the worker generation caused this event by its own call */
    boolean activity() {
        return ACTIVITY.contains(name);
    }

    /**
     * @param field a field name
     * @return the text of the field, or {@code null}
     */
    String text(String field) {
        return text(raw, field);
    }

    /**
     * @param field a field name
     * @return the number of the field, or {@code null}
     */
    Long number(String field) {
        return raw.hasNonNull(field) ? Long.valueOf(raw.get(field).asLong()) : null;
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
