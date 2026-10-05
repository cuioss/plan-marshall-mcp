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
import lombok.experimental.UtilityClass;

/**
 * Reads the CRLF-terminated lines of the HTTP head and of the chunked framing byte by byte, so that
 * a read never waits for more bytes than the line needs.
 */
@UtilityClass
class HttpLines {

    /** Upper bound of one head or framing line. */
    static final int MAX_LINE = 16 * 1024;

    /**
     * Reads one line terminated by LF, an optional CR before it removed.
     *
     * @param in the stream
     * @return the line, or {@code null} at the end of the stream before any byte
     * @throws IOException           on a read failure
     * @throws HttpProtocolException on an over-long line or an end of stream inside a line
     */
    static String readLine(InputStream in) throws IOException {
        var line = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != '\n') {
            if (b < 0) {
                if (line.size() == 0) {
                    return null;
                }
                throw new HttpProtocolException("End of stream inside a line");
            }
            if (line.size() >= MAX_LINE) {
                throw new HttpProtocolException("Line exceeds " + MAX_LINE + " bytes");
            }
            line.write(b);
        }
        var bytes = line.toByteArray();
        var length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    /**
     * Reads one line that must be present.
     *
     * @param in the stream
     * @return the line
     * @throws IOException           on a read failure
     * @throws HttpProtocolException at the end of the stream
     */
    static String requireLine(InputStream in) throws IOException {
        var line = readLine(in);
        if (line == null) {
            throw new HttpProtocolException("Unexpected end of stream");
        }
        return line;
    }
}
