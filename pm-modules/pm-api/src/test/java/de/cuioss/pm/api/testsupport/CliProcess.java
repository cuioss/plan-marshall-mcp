/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A client binary running as a real process with a cleared environment: {@code stdout} is
 * collected raw (for the hygiene checks) and split into lines with their arrival time,
 * {@code stderr} is collected as text.
 */
public final class CliProcess implements AutoCloseable {

    /**
     * One {@code stdout} line.
     *
     * @param text         the line without its LF
     * @param arrivalNanos {@link System#nanoTime()} when it was read
     */
    public record Line(String text, long arrivalNanos) {
    }

    private final Process process;
    private final long startNanos;
    private final ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream rawErr = new ByteArrayOutputStream();
    private final BlockingQueue<Line> lines = new LinkedBlockingQueue<>();
    private final Thread outReader;
    private final Thread errReader;

    private CliProcess(Process process, long startNanos) {
        this.process = process;
        this.startNanos = startNanos;
        this.outReader = Thread.ofPlatform().daemon().start(this::readOut);
        this.errReader = Thread.ofPlatform().daemon().start(() -> copy(process.getErrorStream(), rawErr));
    }

    /**
     * @param command          the command line
     * @param environment      the complete environment
     * @param workingDirectory the working directory
     * @return the started process
     * @throws IOException if it cannot be started
     */
    public static CliProcess start(List<String> command, Map<String, String> environment, Path workingDirectory)
            throws IOException {
        var builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        var startNanos = System.nanoTime();
        return new CliProcess(builder.start(), startNanos);
    }

    /** @return {@link System#nanoTime()} just before the process was started */
    public long startNanos() {
        return startNanos;
    }

    /** @return the process */
    public Process process() {
        return process;
    }

    /**
     * Writes one line to {@code stdin} and flushes it.
     *
     * @param line the line, without LF
     * @throws IOException on a write failure
     */
    public void send(String line) throws IOException {
        var stdin = process.getOutputStream();
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /**
     * Closes {@code stdin}.
     *
     * @throws IOException on a close failure
     */
    public void closeStdin() throws IOException {
        process.getOutputStream().close();
    }

    /**
     * @param timeout the maximum wait
     * @return the next {@code stdout} line, {@code null} on timeout
     * @throws InterruptedException if interrupted
     */
    public Line nextLine(Duration timeout) throws InterruptedException {
        return lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * @param timeout the maximum wait
     * @return the exit code, or {@code null} if the process still runs
     * @throws InterruptedException if interrupted
     */
    public Integer awaitExit(Duration timeout) throws InterruptedException {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return null;
        }
        outReader.join(1000);
        errReader.join(1000);
        return process.exitValue();
    }

    /** @return every byte written to {@code stdout} so far */
    public byte[] stdout() {
        synchronized (rawOut) {
            return rawOut.toByteArray();
        }
    }

    /** @return {@code stderr} so far */
    public String stderr() {
        synchronized (rawErr) {
            return rawErr.toString(StandardCharsets.UTF_8);
        }
    }

    private void readOut() {
        var line = new ByteArrayOutputStream();
        try (var in = process.getInputStream()) {
            int b;
            while ((b = in.read()) >= 0) {
                synchronized (rawOut) {
                    rawOut.write(b);
                }
                if (b == '\n') {
                    lines.add(new Line(line.toString(StandardCharsets.UTF_8), System.nanoTime()));
                    line.reset();
                } else {
                    line.write(b);
                }
            }
        } catch (IOException e) {
            // the process ended
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream target) {
        try (in) {
            var buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                synchronized (target) {
                    target.write(buffer, 0, n);
                }
            }
        } catch (IOException e) {
            // the process ended
        }
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}
