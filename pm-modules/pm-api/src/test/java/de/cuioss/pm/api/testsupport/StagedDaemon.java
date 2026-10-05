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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * A staged {@code <PM_MCP_HOME>/bin/pm-mcpd} for on-demand start tests: a shell script that reports
 * its pid and process group to a marker file and then sleeps, so the test can bring up the fake
 * runtime and check the detachment.
 */
public final class StagedDaemon {

    private final Path home;
    private final Path marker;
    private long pid = -1;

    private StagedDaemon(Path home, Path marker) {
        this.home = home;
        this.marker = marker;
    }

    /**
     * @param directory a scratch directory
     * @return the staged installation
     * @throws IOException on a write failure
     */
    public static StagedDaemon stage(Path directory) throws IOException {
        var home = directory.resolve("home");
        var daemon = Files.createDirectories(home.resolve("bin")).resolve("pm-mcpd");
        Files.writeString(daemon, """
                #!/bin/sh
                echo "staged pm-mcpd started"
                echo "$$ $(ps -o pgid= -p $$ | tr -d ' ')" > "$PM_TEST_MARKER.tmp"
                mv "$PM_TEST_MARKER.tmp" "$PM_TEST_MARKER"
                exec sleep 60
                """);
        if (!daemon.toFile().setExecutable(true)) {
            throw new IOException("Cannot make " + daemon + " executable");
        }
        return new StagedDaemon(home, directory.resolve("daemon-started"));
    }

    /** @return the installation directory */
    public Path home() {
        return home;
    }

    /** @return the environment entries naming the installation and the marker */
    public Map<String, String> environment() {
        return Map.of("PM_MCP_HOME", home.toString(), "PM_TEST_MARKER", marker.toString(), "PATH", "/usr/bin:/bin");
    }

    /**
     * Waits until the staged runtime reported its start.
     *
     * @param timeout the maximum wait
     * @return {@code {pid, pgid}}, or {@code null} on timeout
     * @throws IOException          on a read failure
     * @throws InterruptedException if interrupted
     */
    public long[] awaitStart(Duration timeout) throws IOException, InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(marker)) {
            if (System.nanoTime() > deadline) {
                return null;
            }
            Thread.sleep(2);
        }
        var parts = Files.readString(marker).strip().split(" ");
        pid = Long.parseLong(parts[0]);
        return new long[]{pid, Long.parseLong(parts[1])};
    }

    /** @return {@code true} if the staged runtime was started and still runs */
    public boolean alive() {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Kills the staged runtime. */
    public void kill() {
        if (pid > 0) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }
}
