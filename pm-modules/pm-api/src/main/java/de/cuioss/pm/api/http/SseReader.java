/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Parses a {@code text/event-stream} body (WHATWG HTML, Server-Sent Events) and returns each event
 * as soon as its terminating blank line has arrived. It reads byte by byte and never waits for
 * bytes beyond the current line, so events are delivered unbuffered.
 */
public final class SseReader {

    private static final String DEFAULT_EVENT = "message";

    private final InputStream in;
    private boolean skipLineFeed;
    private String lastEventId;

    /**
     * @param in the decoded body stream
     */
    public SseReader(InputStream in) {
        this.in = Objects.requireNonNull(in, "in");
    }

    /**
     * Reads the next event.
     *
     * @return the event, or {@code null} at the end of the stream
     * @throws IOException on a read failure
     */
    public SseEvent next() throws IOException {
        var pending = new PendingEvent();
        String line;
        while ((line = readLine()) != null) {
            if (line.isEmpty()) {
                if (pending.hasData) {
                    return new SseEvent(pending.event == null ? DEFAULT_EVENT : pending.event, pending.data.toString(),
                            lastEventId);
                }
                pending.event = null;
            } else if (line.charAt(0) != ':') {
                field(pending, line);
            }
        }
        return null;
    }

    /** Applies one field line ({@code name[:[ ]value]}) to the event being read. */
    private void field(PendingEvent pending, String line) {
        var colon = line.indexOf(':');
        var name = colon < 0 ? line : line.substring(0, colon);
        var value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (name) {
            case "data" -> pending.appendData(value);
            case "event" -> pending.event = value;
            case "id" -> {
                if (value.indexOf('\0') < 0) {
                    lastEventId = value;
                }
            }
            default -> {
                // "retry" and unknown fields are ignored
            }
        }
    }

    /** The fields of the event being read. */
    private static final class PendingEvent {
        private final StringBuilder data = new StringBuilder();
        private boolean hasData;
        private String event;

        void appendData(String value) {
            if (hasData) {
                data.append('\n');
            }
            data.append(value);
            hasData = true;
        }
    }

    /** Reads one line ending in CR, LF, or CRLF; returns {@code null} at the end of the stream. */
    private String readLine() throws IOException {
        var line = new ByteArrayOutputStream(128);
        while (true) {
            var b = in.read();
            if (b < 0) {
                return null;
            }
            if (skipLineFeed) {
                skipLineFeed = false;
                if (b == '\n') {
                    continue;
                }
            }
            if (b == '\n') {
                break;
            }
            if (b == '\r') {
                skipLineFeed = true;
                break;
            }
            if (line.size() >= HttpLines.MAX_LINE * 64) {
                throw new HttpProtocolException("Event stream line too long");
            }
            line.write(b);
        }
        return line.toString(StandardCharsets.UTF_8);
    }
}
