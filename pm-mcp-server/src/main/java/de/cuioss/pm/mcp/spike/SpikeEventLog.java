/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;


import io.vertx.core.json.JsonObject;

/**
 * Append-only JSONL record of everything the stub observes. Every measurement of the verifications is
 * derived from this file, never from what a model reports about itself.
 * <p>
 * Each line carries {@code t_ms} (wall clock), {@code mono_ms} (monotonic, since the log was opened),
 * {@code event}, {@code connection} and the fields of the event.
 */
final class SpikeEventLog {

    /** File name of the log inside the run directory. */
    static final String FILE_NAME = "events.jsonl";

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Path file;
    private final long startNanos = System.nanoTime();

    /**
     * @param runDir the run directory, created when missing
     */
    SpikeEventLog(Path runDir) {
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        file = runDir.resolve(FILE_NAME);
    }

    /**
     * @return milliseconds since the log was opened, on the monotonic clock
     */
    long monoMillis() {
        return (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    }

    /**
     * Appends one event.
     *
     * @param event      the event name
     * @param connection the MCP connection the event belongs to, {@code null} when none
     * @param fields     further fields of the event
     */
    synchronized void log(String event, String connection, JsonObject fields) {
        var line = new JsonObject()
                .put("t_ms", System.currentTimeMillis())
                .put("mono_ms", monoMillis())
                .put("event", event)
                .put("connection", connection)
                .mergeIn(fields);
        try {
            Files.writeString(file, line.encode() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
