/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import de.planmarshall.api.MachinePaths;

/**
 * The one on-demand start procedure of both client binaries: create {@code <PM_MCP_BASE>},
 * {@code locks/} and {@code logs/} where absent (mode {@code 0700}, an existing directory is never
 * repaired), take {@code locks/runtime-start.lock}, re-check liveness, start {@code pm-mcpd} by its
 * path detached, and hold the lock until an authenticated round trip succeeds or the runtime start
 * timeout expires.
 */
public final class OnDemandStart implements RuntimeStarter {

    /** The runtime start timeout (Timeouts &amp; Budgets Specification, fixed). */
    public static final Duration RUNTIME_START_TIMEOUT = Duration.ofSeconds(5);

    private static final long FIRST_POLL_NANOS = Duration.ofMillis(2).toNanos();
    private static final long MAX_POLL_NANOS = Duration.ofMillis(50).toNanos();

    /** Liveness: an authenticated round trip. */
    @FunctionalInterface
    public interface Liveness {
        /**
         * @return {@code true} if a runtime answered an authenticated round trip
         * @throws InsecureRuntimeFileException if the runtime-token check refuses a file
         */
        boolean isLive() throws InsecureRuntimeFileException;
    }

    /** Resolves the runtime executable when a start is needed. */
    @FunctionalInterface
    public interface ExecutableSource {
        /**
         * @return the absolute path of {@code pm-mcpd}
         * @throws IOException if the installation cannot be resolved
         */
        Path get() throws IOException;
    }

    /** Waits between liveness probes. */
    @FunctionalInterface
    public interface Sleeper {
        /**
         * @param nanos the time to wait
         * @throws InterruptedException if interrupted
         */
        void sleep(long nanos) throws InterruptedException;
    }

    private final MachinePaths paths;
    private final ExecutableSource daemonExecutable;
    private final Liveness liveness;
    private final DaemonLauncher launcher;
    private final Duration timeout;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private final ReentrantLock inProcess = new ReentrantLock();

    /**
     * @param paths            the machine paths
     * @param daemonExecutable {@code <PM_MCP_HOME>/bin/pm-mcpd}
     * @param liveness         the liveness round trip
     * @param launcher         the detached start
     * @param timeout          the runtime start timeout
     * @param sleeper          the wait between probes
     * @param nanoTime         the monotonic clock
     */
    public OnDemandStart(MachinePaths paths, ExecutableSource daemonExecutable, Liveness liveness, DaemonLauncher launcher,
            Duration timeout, Sleeper sleeper, LongSupplier nanoTime) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.daemonExecutable = Objects.requireNonNull(daemonExecutable, "daemonExecutable");
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * The production procedure.
     *
     * @param paths            the machine paths
     * @param daemonExecutable {@code <PM_MCP_HOME>/bin/pm-mcpd}
     * @param liveness         the liveness round trip
     * @param launcher         the detached start
     * @return the start procedure with the runtime start timeout
     */
    public static OnDemandStart of(MachinePaths paths, ExecutableSource daemonExecutable, Liveness liveness,
            DaemonLauncher launcher) {
        return new OnDemandStart(paths, daemonExecutable, liveness, launcher, RUNTIME_START_TIMEOUT,
                nanos -> Thread.sleep(Duration.ofNanos(nanos)), System::nanoTime);
    }

    @Override
    public void ensureRunning() throws IOException {
        // FileChannel.lock excludes processes, not threads: one start per process at a time
        inProcess.lock();
        try {
            createPrivateDirectory(paths.base());
            createPrivateDirectory(paths.locksDir());
            createPrivateDirectory(paths.logsDir());
            try (var channel = FileChannel.open(paths.runtimeStartLock(),
                         Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                         PosixFilePermissions.asFileAttribute(RuntimeTokenFile.FILE_MODE));
                 var _ = channel.lock()) {
                if (liveness.isLive()) {
                    return;
                }
                var executable = daemonExecutable.get();
                var daemon = launcher.launch(executable, paths.daemonLog());
                awaitRoundTrip(executable, daemon);
            }
        } finally {
            inProcess.unlock();
        }
    }

    private void awaitRoundTrip(Path daemonExecutable, DaemonLauncher.Launched daemon) throws IOException {
        var deadline = nanoTime.getAsLong() + timeout.toNanos();
        var pause = FIRST_POLL_NANOS;
        while (!liveness.isLive()) {
            var exit = daemon.exitCode();
            if (exit.isPresent() && exit.getAsInt() != 0) {
                throw new RuntimeUnavailableException("The runtime " + daemonExecutable + " (pid " + daemon.pid()
                        + ") exited with code " + exit.getAsInt() + " before it answered; see " + paths.daemonLog());
            }
            var now = nanoTime.getAsLong();
            if (now >= deadline) {
                throw new RuntimeUnavailableException("The runtime " + daemonExecutable + " (pid " + daemon.pid()
                        + ") did not answer within " + timeout.toMillis() + " ms; see " + paths.daemonLog());
            }
            try {
                sleeper.sleep(Math.min(pause, deadline - now));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeUnavailableException("Interrupted while waiting for the runtime", e);
            }
            pause = Math.min(pause * 2, MAX_POLL_NANOS);
        }
    }

    /**
     * Creates a directory with mode {@code 0700} if it is absent; an existing one is left as it is
     * (the runtime's permission check refuses a broader mode, it is never repaired here).
     *
     * @param directory the directory
     * @throws IOException if it cannot be created
     */
    static void createPrivateDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        var parent = directory.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        try {
            // the attribute is narrowed by the umask, never widened; the explicit mode follows
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(RuntimeTokenFile.DIRECTORY_MODE));
            Files.setPosixFilePermissions(directory, RuntimeTokenFile.DIRECTORY_MODE);
        } catch (FileAlreadyExistsException e) {
            // created concurrently by another client: left as it is
        }
    }
}
