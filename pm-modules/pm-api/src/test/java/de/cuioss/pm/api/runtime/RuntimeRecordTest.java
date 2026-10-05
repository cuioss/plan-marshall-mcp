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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RuntimeRecord")
class RuntimeRecordTest {

    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        file = Files.createTempFile("runtime", ".json");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("reads the record of a live process")
    void live() throws Exception {
        var self = ProcessHandle.current();
        var start = self.info().startInstant().orElseThrow();
        Files.writeString(file, "{\"pid\":" + self.pid() + ",\"start_instant\":\"" + start + "\",\"version\":\"1.0\"}");

        var record = RuntimeRecord.read(file).orElseThrow();

        assertEquals(self.pid(), record.pid());
        assertEquals("1.0", record.version());
        assertTrue(record.processAlive());
    }

    @Test
    @DisplayName("names no live runtime for a reused pid with another start instant or a dead pid")
    void notLive() {
        var self = ProcessHandle.current().pid();

        assertFalse(new RuntimeRecord(self, Instant.parse("2001-01-01T00:00:00Z"), null).processAlive());
        assertTrue(new RuntimeRecord(self, null, null).processAlive());
        assertFalse(new RuntimeRecord(Integer.MAX_VALUE - 1L, null, null).processAlive());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "{\"pid\":\"x\"}", "{\"pid\":1.5}", "{\"pid\":1,\"start_instant\":\"bad\"}"})
    @DisplayName("treats an unreadable record as absent")
    void unreadable(String content) throws Exception {
        Files.writeString(file, content);

        assertTrue(RuntimeRecord.read(file).isEmpty());
    }

    @Test
    @DisplayName("treats a missing record as absent")
    void missing() throws Exception {
        Files.delete(file);

        assertTrue(RuntimeRecord.read(file).isEmpty());
    }
}
