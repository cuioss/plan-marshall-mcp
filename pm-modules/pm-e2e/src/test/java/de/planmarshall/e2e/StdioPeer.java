/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A process spoken to over newline-delimited JSON-RPC on {@code stdin}/{@code stdout}, as a host speaks to
 * {@code pm-mcp serve}; {@code stderr} is collected line by line.
 */
final class StdioPeer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process process;
    private final BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
    private final List<String> stderr = new CopyOnWriteArrayList<>();

    private StdioPeer(Process process) {
        this.process = process;
        Thread.ofPlatform().daemon().start(() -> read(process.getInputStream(), stdout));
        Thread.ofPlatform().daemon().start(() -> read(process.getErrorStream(), stderr));
    }

    /**
     * @param command     the command line
     * @param environment the complete environment
     * @param directory   the working directory
     * @return the started process
     * @throws IOException if it cannot be started
     */
    static StdioPeer start(List<String> command, Map<String, String> environment, Path directory)
            throws IOException {
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        return new StdioPeer(builder.start());
    }

    /**
     * Writes one message and a newline.
     *
     * @param message the JSON-RPC message
     * @throws IOException on a write failure
     */
    void send(String message) throws IOException {
        var in = process.getOutputStream();
        in.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    /**
     * Reads {@code stdout} until the response with the given id, skipping notifications and other messages.
     *
     * @param id      the JSON-RPC id
     * @param timeout the maximum wait
     * @return the response
     * @throws IOException          if no response arrives in time or a line is no JSON
     * @throws InterruptedException if interrupted
     */
    JsonNode response(int id, Duration timeout) throws IOException, InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var line = stdout.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new IOException("no response " + id + " within " + timeout + "; stderr: " + stderr);
            }
            var message = JSON.readTree(line);
            if (message.path("id").asInt(-1) == id && (message.has("result") || message.has("error"))) {
                return message;
            }
        }
    }

    /**
     * Closes {@code stdin} and waits for the exit.
     *
     * @param timeout the maximum wait
     * @return the exit code, or {@code -1} if the process still runs
     * @throws IOException          on a close failure
     * @throws InterruptedException if interrupted
     */
    int closeAndWait(Duration timeout) throws IOException, InterruptedException {
        process.getOutputStream().close();
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) ? process.exitValue() : -1;
    }

    /** @return the {@code stderr} lines so far */
    List<String> stderr() {
        return List.copyOf(stderr);
    }

    /** @return the process */
    Process process() {
        return process;
    }

    private static void read(InputStream stream, Collection<String> sink) {
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                sink.add(line);
            }
        } catch (IOException _) {
            // the process ended
        }
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
