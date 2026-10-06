/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One running worker generation: the harness process (started through {@code pm-exec}, which replaces itself with
 * the harness, so the pid is the harness's), its job, and its arrival-stamped output
 * ({@code harness-<worker>g<generation>.jsonl}: {@code {"t_ms":…,"s":"out|err","line":…}}, as in Part A).
 */
final class Worker {

    private final String worker;
    private final int generation;
    private final String role;
    private final String promptKind;
    private final SpikeDaemon.Job job;
    private final Process process;
    private final Path workerDir;
    private final long startedMs;
    private final List<Line> stdout = Collections.synchronizedList(new ArrayList<>());
    private final List<Line> stderr = Collections.synchronizedList(new ArrayList<>());
    private final Writer log;
    private volatile long exitMs = -1;
    private volatile boolean replaced;
    private volatile boolean handled;

    Worker(String worker, int generation, String role, String promptKind, SpikeDaemon.Job job, Process process,
            Path workerDir, Path logFile) throws IOException {
        this.worker = worker;
        this.generation = generation;
        this.role = role;
        this.promptKind = promptKind;
        this.job = job;
        this.process = process;
        this.workerDir = workerDir;
        this.startedMs = System.currentTimeMillis();
        this.log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
        pump(process.getInputStream(), "out", stdout);
        pump(process.getErrorStream(), "err", stderr);
        process.onExit().thenRun(() -> exitMs = System.currentTimeMillis());
    }

    /**
     * One output line with its arrival time.
     *
     * @param tMs  the arrival time
     * @param text the line
     */
    record Line(long tMs, String text) {
    }

    private void pump(InputStream in, String stream, List<Line> lines) {
        Thread.ofVirtual().name("pump-" + tag() + "-" + stream).start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                    var now = System.currentTimeMillis();
                    lines.add(new Line(now, line));
                    var record = StubEvent.JSON.createObjectNode().put("t_ms", now)
                            .put("s", stream).put("line", line).toString();
                    synchronized (log) {
                        log.write(record);
                        log.write('\n');
                        log.flush();
                    }
                }
            } catch (IOException _) {
                // the process ended
            }
        });
    }

    String worker() {
        return worker;
    }

    int generation() {
        return generation;
    }

    String role() {
        return role;
    }

    String promptKind() {
        return promptKind;
    }

    SpikeDaemon.Job job() {
        return job;
    }

    Path workerDir() {
        return workerDir;
    }

    /** @return {@code w1g2} */
    String tag() {
        return worker + "g" + generation;
    }

    long pid() {
        return process.pid();
    }

    long startedMs() {
        return startedMs;
    }

    boolean alive() {
        return process.isAlive();
    }

    /** @return when the process was seen to exit, {@code -1} while it runs */
    long exitMs() {
        return exitMs;
    }

    /** @return the exit code, negative for a signal as in Part A ({@code -9}) */
    Integer exitCode() {
        if (process.isAlive()) {
            return null;
        }
        var code = process.exitValue();
        return code > 128 ? -(code - 128) : code;
    }

    /** @return whether the generation was replaced (fenced) */
    boolean replaced() {
        return replaced;
    }

    void markReplaced() {
        replaced = true;
    }

    /** @return whether the supervisor handled the end of this generation */
    boolean handled() {
        return handled;
    }

    void markHandled() {
        handled = true;
    }

    /** @return the standard output lines so far */
    List<String> stdout() {
        return stdoutLines().stream().map(Line::text).toList();
    }

    /** @return the standard output lines so far, with their arrival times */
    List<Line> stdoutLines() {
        synchronized (stdout) {
            return List.copyOf(stdout);
        }
    }

    /** @return the standard error lines so far */
    List<String> stderr() {
        synchronized (stderr) {
            return stderr.stream().map(Line::text).toList();
        }
    }

    /** @return the launch report of {@code pm-exec}, or {@code null} */
    String launchReport() {
        return stderr().stream().filter(line -> line.contains("\"pm_exec\"")).findFirst().orElse(null);
    }

    /** Sends SIGKILL to the harness. */
    void kill() {
        process.destroyForcibly();
    }

    /**
     * Sends SIGTERM and waits; SIGKILL after the bound.
     *
     * @param bound how long to wait for the exit
     * @return the milliseconds until the exit after SIGTERM, or {@code -1} if SIGKILL was needed
     */
    long terminate(Duration bound) {
        if (!process.isAlive()) {
            return 0;
        }
        var start = System.nanoTime();
        process.destroy();
        try {
            if (process.waitFor(bound.toMillis(), TimeUnit.MILLISECONDS)) {
                return (System.nanoTime() - start) / 1_000_000;
            }
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        return -1;
    }

    /**
     * Waits for the end of the process.
     *
     * @param bound the bound
     * @return whether it ended
     */
    boolean awaitExit(Duration bound) {
        try {
            return process.waitFor(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Closes the output log once the pumps drained. */
    void closeLog() {
        try {
            Thread.sleep(200);
            synchronized (log) {
                log.close();
            }
        } catch (IOException _) {
            // nothing to keep
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
