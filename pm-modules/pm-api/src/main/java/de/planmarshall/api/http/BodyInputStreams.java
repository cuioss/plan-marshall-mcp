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

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * The three body framings of an HTTP/1.1 response: {@code Content-Length}, {@code chunked}, and
 * until the connection closes. Every stream returns the bytes that have arrived instead of waiting
 * to fill the caller's buffer, so a Server-Sent Events body is delivered as it arrives.
 */
@UtilityClass
class BodyInputStreams {

    /**
     * @param in     the connection stream positioned after the head
     * @param length the content length
     * @return a stream ending after {@code length} bytes
     */
    static InputStream bounded(InputStream in, long length) {
        return new Bounded(in, length);
    }

    /**
     * @param in the connection stream positioned after the head
     * @return a stream decoding the chunked transfer coding
     */
    static InputStream chunked(InputStream in) {
        return new Chunked(in);
    }

    /**
     * @return a stream without bytes
     */
    static InputStream empty() {
        return InputStream.nullInputStream();
    }

    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long remaining;

        Bounded(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            var b = in.read();
            if (b < 0) {
                throw new HttpProtocolException("End of stream inside a body of fixed length");
            }
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            var n = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (n < 0) {
                throw new HttpProtocolException("End of stream inside a body of fixed length");
            }
            remaining -= n;
            return n;
        }
    }

    private static final class Chunked extends InputStream {
        private final InputStream in;
        private long remaining;
        private boolean finished;

        Chunked(InputStream in) {
            this.in = in;
        }

        /** @return {@code false} once the last chunk and the trailers are read */
        private boolean ensureChunk() throws IOException {
            if (finished) {
                return false;
            }
            if (remaining > 0) {
                return true;
            }
            var sizeLine = HttpLines.requireLine(in);
            var extension = sizeLine.indexOf(';');
            var hex = (extension >= 0 ? sizeLine.substring(0, extension) : sizeLine).trim().toLowerCase(Locale.ROOT);
            try {
                remaining = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                throw new HttpProtocolException("Invalid chunk size: " + sizeLine);
            }
            if (remaining < 0) {
                throw new HttpProtocolException("Invalid chunk size: " + sizeLine);
            }
            if (remaining == 0) {
                String trailer;
                do {
                    trailer = HttpLines.requireLine(in);
                } while (!trailer.isEmpty());
                finished = true;
                return false;
            }
            return true;
        }

        private void endOfChunk() throws IOException {
            if (remaining == 0 && !HttpLines.requireLine(in).isEmpty()) {
                throw new HttpProtocolException("Chunk data not followed by CRLF");
            }
        }

        @Override
        public int read() throws IOException {
            if (!ensureChunk()) {
                return -1;
            }
            var b = in.read();
            if (b < 0) {
                throw new HttpProtocolException("End of stream inside a chunk");
            }
            remaining--;
            endOfChunk();
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!ensureChunk()) {
                return -1;
            }
            var n = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (n < 0) {
                throw new HttpProtocolException("End of stream inside a chunk");
            }
            remaining -= n;
            endOfChunk();
            return n;
        }
    }
}
