/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import de.cuioss.pm.api.json.JsonNumber;
import de.cuioss.pm.api.json.JsonTree;

/**
 * Read-only codec of the runtime record {@code <PM_MCP_BASE>/state/runtime.json}, one of the files
 * the client binaries read directly. It serves only the "not running" answer: a record whose
 * process is gone, or whose start instant differs (PID reuse), names no live runtime.
 *
 * @param pid           the runtime's process id
 * @param startInstant  the process start instant, {@code null} when not recorded
 * @param version       the runtime version, {@code null} when not recorded
 */
public record RuntimeRecord(long pid, Instant startInstant, String version) {

    /**
     * Reads the record.
     *
     * @param file {@code state/runtime.json}
     * @return the record, empty if the file is absent or unreadable as a record
     * @throws IOException on a read failure other than absence
     */
    public static Optional<RuntimeRecord> read(Path file) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        try {
            var root = JsonTree.asObject(JsonTree.parse(bytes));
            if (root == null || !(root.get("pid") instanceof JsonNumber pid)) {
                return Optional.empty();
            }
            var start = JsonTree.string(root, "start_instant");
            return Optional.of(new RuntimeRecord(pid.longValue(), start == null ? null : Instant.parse(start),
                    JsonTree.string(root, "version")));
        } catch (IOException | NumberFormatException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * @return {@code true} if the recorded process is alive and, where recorded, started at the
     *         recorded instant
     */
    public boolean processAlive() {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(handle -> startInstant == null
                || handle.info().startInstant().map(actual -> actual.truncatedTo(ChronoUnit.SECONDS)
                .equals(startInstant.truncatedTo(ChronoUnit.SECONDS))).orElse(true))
                .orElse(false);
    }
}
