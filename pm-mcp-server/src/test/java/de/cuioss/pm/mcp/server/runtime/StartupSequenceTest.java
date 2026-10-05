/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;


import de.cuioss.pm.api.MachinePaths;
import de.cuioss.pm.mcp.server.test.TestBases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Startup steps 1 to 5")
class StartupSequenceTest {

    private static final String USER = System.getProperty("user.name");

    private Path base;
    private MachinePaths paths;

    @BeforeEach
    void createBase() {
        base = TestBases.create("pmu");
        paths = new MachinePaths(base, MachinePaths.Os.current());
    }

    @AfterEach
    void deleteBase() {
        TestBases.delete(base);
    }

    private StartupSequence.Outcome run() throws IOException {
        return new StartupSequence(paths, USER).run(new SecureRandom());
    }

    @Nested
    @DisplayName("on a clean base")
    class Clean {

        @Test
        @DisplayName("creates the private directories and a fresh 256-bit token")
        void shouldPrepareBase() throws Exception {
            var outcome = run();

            var ready = assertInstanceOf(StartupSequence.Outcome.Ready.class, outcome);
            try (var lock = ready.lock()) {
                assertTrue(lock.isHeld());
                for (var dir : new Path[]{base, paths.runDir(), paths.stateDir(), base.resolve("enrolments"),
                        paths.locksDir()}) {
                    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)), dir
                            .toString());
                }
                assertEquals("rw-------",
                        PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.runtimeToken())));
                assertEquals(43, Files.readString(paths.runtimeToken()).length());
                assertFalse(Files.exists(paths.runtimeToken().resolveSibling("runtime.token.tmp")));
            }
        }

        @Test
        @DisplayName("replaces the token at every start")
        void shouldReplaceToken() throws Exception {
            String first;
            try (var _ = ((StartupSequence.Outcome.Ready) run()).lock()) {
                first = Files.readString(paths.runtimeToken());
            }
            try (var _ = ((StartupSequence.Outcome.Ready) run()).lock()) {
                assertNotEquals(first, Files.readString(paths.runtimeToken()));
            }
        }
    }

    @Test
    @DisplayName("a runtime that loses the lock writes nothing")
    void shouldLoseLockWithoutWriting() throws Exception {
        try (var _ = ((StartupSequence.Outcome.Ready) run()).lock()) {
            var token = Files.readString(paths.runtimeToken());
            var modified = Files.getLastModifiedTime(paths.runtimeToken());

            var second = run();

            assertInstanceOf(StartupSequence.Outcome.LockHeld.class, second);
            assertEquals(token, Files.readString(paths.runtimeToken()));
            assertEquals(modified, Files.getLastModifiedTime(paths.runtimeToken()));
        }
    }

    @Test
    @DisplayName("removes a stale socket and runtime record while holding the lock")
    void shouldRemoveStaleFiles() throws Exception {
        Files.createDirectories(paths.runDir(), PosixModes.directoryAttribute());
        Files.createDirectories(paths.stateDir(), PosixModes.directoryAttribute());
        Files.createFile(paths.socket(), PosixModes.fileAttribute());
        Files.createFile(paths.runtimeRecord(), PosixModes.fileAttribute());
        Files.setPosixFilePermissions(base, PosixModes.DIRECTORY);

        try (var _ = ((StartupSequence.Outcome.Ready) run()).lock()) {
            assertFalse(Files.exists(paths.socket(), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(paths.runtimeRecord()));
        }
    }

    @Test
    @DisplayName("refuses an over-long socket path before writing a token")
    void shouldRefuseLongSocketPath() throws Exception {
        var longBase = base.resolve("x".repeat(MachinePaths.Os.current().sunPathLimit()));
        paths = new MachinePaths(longBase, MachinePaths.Os.current());

        var outcome = run();

        var refused = assertInstanceOf(StartupSequence.Outcome.Refused.class, outcome);
        assertTrue(refused.diagnostic().contains(paths.socket().toString()), refused.diagnostic());
        assertTrue(refused.diagnostic().contains("PM_MCP_BASE"));
        assertFalse(Files.exists(paths.runtimeToken()));
        assertFalse(Files.exists(paths.runDir()));
    }

    @Nested
    @DisplayName("refuses a base with broken permissions")
    class Permissions {

        @ParameterizedTest(name = "{0} with mode 0755")
        @ValueSource(strings = {"", "run", "enrolments"})
        @DisplayName("a directory with a broader mode")
        void shouldRefuseBroadDirectory(String dir) throws Exception {
            ((StartupSequence.Outcome.Ready) run()).lock().close();
            var target = dir.isEmpty() ? base : base.resolve(dir);
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));

            var outcome = run();

            var refused = assertInstanceOf(StartupSequence.Outcome.Refused.class, outcome);
            assertTrue(refused.diagnostic().contains("0755"), refused.diagnostic());
        }

        @Test
        @DisplayName("a state file with a broader mode")
        void shouldRefuseBroadStateFile() throws Exception {
            ((StartupSequence.Outcome.Ready) run()).lock().close();
            var file = Files.createFile(paths.stateDir().resolve("machine-config.json"));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

            var refused = assertInstanceOf(StartupSequence.Outcome.Refused.class, run());
            assertTrue(refused.diagnostic().contains("machine-config.json"), refused.diagnostic());
        }

        @Test
        @DisplayName("a symlinked run directory")
        void shouldRefuseSymlink() throws Exception {
            ((StartupSequence.Outcome.Ready) run()).lock().close();
            var elsewhere = Files.createDirectory(base.resolve("elsewhere"), PosixModes.directoryAttribute());
            TestBases.delete(paths.runDir());
            Files.createSymbolicLink(paths.runDir(), elsewhere);

            var refused = assertInstanceOf(StartupSequence.Outcome.Refused.class, run());
            assertTrue(refused.diagnostic().contains("symbolic link"), refused.diagnostic());
        }

        @Test
        @DisplayName("a directory owned by another user")
        void shouldRefuseForeignOwner() throws Exception {
            ((StartupSequence.Outcome.Ready) run()).lock().close();

            var outcome = new StartupSequence(paths, "somebody-else").run(new SecureRandom());

            var refused = assertInstanceOf(StartupSequence.Outcome.Refused.class, outcome);
            assertTrue(refused.diagnostic().contains("owned by"), refused.diagnostic());
        }
    }

    @Test
    @DisplayName("formats permission sets in octal")
    void shouldFormatOctal() {
        assertEquals("0700", PosixModes.octal(PosixModes.DIRECTORY));
        assertEquals("0600", PosixModes.octal(PosixModes.FILE));
        assertEquals("0755", PosixModes.octal(PosixFilePermissions.fromString("rwxr-xr-x")));
    }
}
