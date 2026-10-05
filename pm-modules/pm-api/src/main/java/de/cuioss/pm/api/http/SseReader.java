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
        var data = new StringBuilder();
        var hasData = false;
        String event = null;
        String line;
        while ((line = readLine()) != null) {
            if (line.isEmpty()) {
                if (hasData) {
                    return new SseEvent(event == null ? DEFAULT_EVENT : event, data.toString(), lastEventId);
                }
                event = null;
                continue;
            }
            if (line.charAt(0) == ':') {
                continue;
            }
            var colon = line.indexOf(':');
            var field = colon < 0 ? line : line.substring(0, colon);
            var value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "data" -> {
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(value);
                    hasData = true;
                }
                case "event" -> event = value;
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
        return null;
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
