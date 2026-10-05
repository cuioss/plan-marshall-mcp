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
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Follows the stub's event log while the daemon appends to it: each {@link #poll()} returns the complete lines
 * written since the last one. All events seen are kept for the evaluation.
 */
final class EventTail {

    private final Path file;
    private final List<StubEvent> all = new ArrayList<>();
    private long offset;
    private byte[] partial = new byte[0];

    EventTail(Path file) {
        this.file = file;
    }

    /** @return the new events, in log order */
    List<StubEvent> poll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try (var in = new RandomAccessFile(file.toFile(), "r")) {
            var length = in.length();
            if (length <= offset) {
                return List.of();
            }
            var bytes = new byte[(int) (length - offset)];
            in.seek(offset);
            in.readFully(bytes);
            offset = length;
            var joined = new byte[partial.length + bytes.length];
            System.arraycopy(partial, 0, joined, 0, partial.length);
            System.arraycopy(bytes, 0, joined, partial.length, bytes.length);
            var fresh = new ArrayList<StubEvent>();
            var start = 0;
            for (var i = 0; i < joined.length; i++) {
                if (joined[i] == '\n') {
                    var line = new String(joined, start, i - start, StandardCharsets.UTF_8);
                    if (!line.isBlank()) {
                        fresh.add(StubEvent.parse(line));
                    }
                    start = i + 1;
                }
            }
            partial = Arrays.copyOfRange(joined, start, joined.length);
            all.addAll(fresh);
            return fresh;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return every event seen so far */
    List<StubEvent> all() {
        return List.copyOf(all);
    }
}
