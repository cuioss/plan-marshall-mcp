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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import de.planmarshall.api.testsupport.RuntimeFixture;

@DisplayName("OnDemandStart")
@EnabledOnOs({OS.MAC, OS.LINUX})
class OnDemandStartTest {

    private static final Path DAEMON = Path.of("/opt/pm/bin/pm-mcpd");

    private RuntimeFixture fixture;
    private final AtomicLong clock = new AtomicLong();
    private final List<Long> sleeps = new ArrayList<>();
    private final List<Path> launched = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        fixture = RuntimeFixture.create();
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    /** A launcher whose process exits with the given code (empty: keeps running). */
    private DaemonLauncher launcher(OptionalInt exit) {
        return (executable, log) -> {
            launched.add(executable);
            launched.add(log);
            return new DaemonLauncher.Launched() {
                @Override
                public int pid() {
                    return 4242;
                }

                @Override
                public OptionalInt exitCode() {
                    return exit;
                }
            };
        };
    }

    private OnDemandStart start(OnDemandStart.Liveness liveness, DaemonLauncher launcher) {
        return new OnDemandStart(fixture.paths(), () -> DAEMON, liveness, launcher, Duration.ofSeconds(5), nanos -> {
            sleeps.add(nanos);
            clock.addAndGet(nanos);
        }, clock::get);
    }

    @Test
    @DisplayName("creates <PM_MCP_BASE>, locks/ and logs/ with mode 0700 and the start lock with 0600")
    void createsPrivateDirectories() throws Exception {
        start(() -> true, launcher(OptionalInt.empty())).ensureRunning();

        for (var directory : List.of(fixture.base(), fixture.paths().locksDir(), fixture.paths().logsDir())) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
        }
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(fixture.paths().runtimeStartLock())));
    }

    @Test
    @DisplayName("never repairs an existing broader <PM_MCP_BASE>")
    void keepsExisting() throws Exception {
        Files.createDirectories(fixture.base());
        Files.setPosixFilePermissions(fixture.base(), PosixFilePermissions.fromString("rwxr-xr-x"));

        start(() -> true, launcher(OptionalInt.empty())).ensureRunning();

        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(fixture.base())));
    }

    @Test
    @DisplayName("starts nothing when the re-check under the lock finds a live runtime")
    void recheck() throws Exception {
        start(() -> true, launcher(OptionalInt.empty())).ensureRunning();

        assertTrue(launched.isEmpty());
    }

    @Test
    @DisplayName("launches pm-mcpd by its path with the daemon log and waits for the round trip")
    void launchesAndWaits() throws Exception {
        var probes = new AtomicInteger();

        start(() -> probes.incrementAndGet() > 4, launcher(OptionalInt.empty())).ensureRunning();

        assertEquals(List.of(DAEMON, fixture.paths().daemonLog()), launched);
        assertEquals(5, probes.get());
        assertEquals(List.of(2_000_000L, 4_000_000L, 8_000_000L), sleeps);
    }

    @Test
    @DisplayName("holds runtime-start.lock until the round trip succeeded")
    void holdsLock() throws Exception {
        var lockFree = new AtomicBoolean(true);
        var probes = new AtomicInteger();

        start(() -> {
            if (probes.incrementAndGet() > 1) {
                lockFree.set(lockFree.get() && tryLock(fixture.paths().runtimeStartLock()));
                return true;
            }
            return false;
        }, launcher(OptionalInt.empty())).ensureRunning();

        assertFalse(lockFree.get());
        assertTrue(tryLock(fixture.paths().runtimeStartLock()));
    }

    private static boolean tryLock(Path file) {
        // a lock held by this JVM shows as OverlappingFileLockException
        try (var channel = FileChannel.open(file, Set.of(StandardOpenOption.WRITE)); var lock = channel.tryLock()) {
            return lock != null;
        } catch (OverlappingFileLockException | IOException e) {
            return false;
        }
    }

    @Test
    @DisplayName("gives up after the runtime start timeout")
    void timesOut() {
        var start = start(() -> false, launcher(OptionalInt.empty()));

        var e = assertThrows(RuntimeUnavailableException.class, start::ensureRunning);
        assertTrue(e.getMessage().contains("did not answer within 5000 ms"));
        assertEquals(Duration.ofSeconds(5).toNanos(), clock.get());
    }

    @Test
    @DisplayName("fails at once when the runtime exits with a non-zero code")
    void earlyExit() {
        var start = start(() -> false, launcher(OptionalInt.of(77)));

        var e = assertThrows(RuntimeUnavailableException.class, start::ensureRunning);
        assertTrue(e.getMessage().contains("exited with code 77"));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    @DisplayName("keeps waiting when the runtime exits 0 (another runtime holds the singleton lock)")
    void exitZero() throws Exception {
        var probes = new AtomicInteger();

        start(() -> probes.incrementAndGet() > 2, launcher(OptionalInt.of(0))).ensureRunning();

        assertEquals(3, probes.get());
    }

    @Test
    @DisplayName("reports an interrupt while waiting")
    void interrupted() {
        var start = new OnDemandStart(fixture.paths(), () -> DAEMON, () -> false, launcher(OptionalInt.empty()),
                Duration.ofSeconds(5), nanos -> {
                    throw new InterruptedException();
                }, clock::get);

        assertThrows(RuntimeUnavailableException.class, start::ensureRunning);
        assertTrue(Thread.interrupted());
    }

    @Test
    @DisplayName("the production factory uses the runtime start timeout")
    void factory() throws Exception {
        OnDemandStart.of(fixture.paths(), () -> DAEMON, () -> true, launcher(OptionalInt.empty())).ensureRunning();

        assertEquals(OnDemandStart.RUNTIME_START_TIMEOUT, Duration.ofSeconds(5));
    }
}
