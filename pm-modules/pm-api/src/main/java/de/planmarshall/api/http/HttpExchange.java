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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One open request/response exchange: the response head has been read, the body is read through
 * {@link #body()}, {@link #readBody()}, or {@link #events()}. Closing it closes the connection.
 */
public final class HttpExchange implements Closeable {

    private final Closeable connection;
    private final int status;
    private final Map<String, List<String>> headers;
    private final InputStream body;

    HttpExchange(Closeable connection, int status, Map<String, List<String>> headers, InputStream body) {
        this.connection = connection;
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    /** @return the HTTP status code */
    public int status() {
        return status;
    }

    /**
     * @param name the header name, case-insensitive
     * @return the first value of the header, or {@code null}
     */
    public String header(String name) {
        var values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** @return {@code true} if the body is a {@code text/event-stream} */
    public boolean isEventStream() {
        var type = header("Content-Type");
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("text/event-stream");
    }

    /** @return the decoded body stream */
    public InputStream body() {
        return body;
    }

    /**
     * @return the whole body
     * @throws IOException on a read failure
     */
    public byte[] readBody() throws IOException {
        return body.readAllBytes();
    }

    /**
     * @return the whole body as UTF-8 text
     * @throws IOException on a read failure
     */
    public String readBodyText() throws IOException {
        return new String(readBody(), StandardCharsets.UTF_8);
    }

    /** @return a reader of the body as Server-Sent Events */
    public SseReader events() {
        return new SseReader(body);
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }
}
