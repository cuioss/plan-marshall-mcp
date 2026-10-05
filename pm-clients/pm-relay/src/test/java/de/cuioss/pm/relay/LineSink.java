/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.relay;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@link PrintWriter} target that collects complete lines and records whether each line was
 * flushed before the next write began.
 */
final class LineSink extends Writer {

    private final StringBuilder pending = new StringBuilder();
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final StringBuilder all = new StringBuilder();

    /** @return a print writer writing into this sink */
    PrintWriter writer() {
        return new PrintWriter(this, false);
    }

    @Override
    public synchronized void write(char[] buffer, int offset, int length) {
        pending.append(buffer, offset, length);
        all.append(buffer, offset, length);
    }

    @Override
    public synchronized void flush() {
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            lines.add(pending.substring(0, newline));
            pending.delete(0, newline + 1);
        }
    }

    @Override
    public void close() {
        flush();
    }

    /**
     * @param seconds the maximum wait
     * @return the next flushed line, {@code null} on timeout
     * @throws InterruptedException if interrupted
     */
    String next(long seconds) throws InterruptedException {
        return lines.poll(seconds, TimeUnit.SECONDS);
    }

    /** @return everything written so far */
    synchronized String text() {
        return all.toString();
    }
}
