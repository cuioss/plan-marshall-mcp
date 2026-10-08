/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SseReader")
class SseReaderTest {

    private static SseReader reader(String text) {
        return new SseReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    @DisplayName("accepts every line terminator")
    void terminators(String eol) throws Exception {
        var events = reader("event: e" + eol + "data: x" + eol + eol + "data: y" + eol + eol);

        assertEquals(new SseEvent("e", "x", null), events.next());
        assertEquals(new SseEvent("message", "y", null), events.next());
        assertNull(events.next());
    }

    @Test
    @DisplayName("joins data lines, keeps the last id, skips comments, unknown fields and empty events")
    void fields() throws Exception {
        var events = reader(": comment\nretry: 5\nevent: skipped\n\nid: 7\ndata\ndata:a\ndata:  b\nfoo: bar\n\nid: x\0y\ndata: c\n\n");

        assertEquals(new SseEvent("message", "\na\n b", "7"), events.next());
        assertEquals(new SseEvent("message", "c", "7"), events.next());
        assertNull(events.next());
    }

    @Test
    @DisplayName("drops an event without its blank line at the end of the stream")
    void incomplete() throws Exception {
        assertNull(reader("data: x\n").next());
    }

    @Test
    @DisplayName("refuses an unbounded line")
    void tooLong() {
        var endless = new InputStream() {
            @Override
            public int read() {
                return 'a';
            }
        };

        assertThrows(HttpProtocolException.class, () -> new SseReader(endless).next());
    }
}
