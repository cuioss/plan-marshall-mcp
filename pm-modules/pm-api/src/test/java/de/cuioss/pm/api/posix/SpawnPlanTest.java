/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.posix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import de.cuioss.pm.api.MachinePaths;

@DisplayName("SpawnPlan and PosixPlatform")
class SpawnPlanTest {

    private static final Path DAEMON = Path.of("/opt/pm/bin/pm-mcpd");
    private static final Path LOG = Path.of("/home/u/.plan-marshall-mcp/logs/daemon.log");

    @Nested
    @DisplayName("Platform constants (verified against the system headers)")
    class Constants {

        @ParameterizedTest(name = "{0}")
        @CsvSource({"MACOS, 0x0400, 0x4000, 0x440C, 0x209, 8, 8, 4",
                "LINUX, 0x0080, 0x0000, 0x008C, 0x441, 336, 80, 128"})
        @DisplayName("flags, open flags and structure sizes")
        void constants(PosixPlatform platform, String setsid, String cloexec, String detached, String append,
                long attrSize, long actionsSize, long sigsetSize) {
            assertEquals(Integer.decode(setsid).shortValue(), platform.setsidFlag());
            assertEquals(Integer.decode(cloexec).shortValue(), platform.cloexecDefaultFlag());
            assertEquals(Integer.decode(detached).shortValue(), platform.detachedSpawnFlags());
            assertEquals(Integer.decode(append), platform.appendFlags());
            assertEquals(attrSize, platform.spawnAttrSize());
            assertEquals(actionsSize, platform.fileActionsSize());
            assertEquals(sigsetSize, platform.sigsetSize());
        }

        @Test
        @DisplayName("mode_t is 16 bits on macOS and 32 bits on Linux")
        void modeLayout() {
            assertEquals(ValueLayout.JAVA_SHORT, PosixPlatform.MACOS.modeLayout());
            assertEquals(ValueLayout.JAVA_INT, PosixPlatform.LINUX.modeLayout());
        }

        @Test
        @DisplayName("maps the operating system")
        void mapping() {
            assertEquals(PosixPlatform.MACOS, PosixPlatform.of(MachinePaths.Os.MACOS));
            assertEquals(PosixPlatform.LINUX, PosixPlatform.of(MachinePaths.Os.LINUX));
            assertEquals(PosixPlatform.of(MachinePaths.Os.current()), PosixPlatform.current());
        }
    }

    @Nested
    @DisplayName("Detached daemon plan")
    class Daemon {

        @Test
        @DisplayName("redirects stdin from /dev/null and appends stdout and stderr to the log (macOS)")
        void macos() {
            var plan = SpawnPlan.detachedDaemon(PosixPlatform.MACOS, DAEMON, LOG, Map.of("A", "1"));

            assertEquals(List.of(new SpawnPlan.Open(0, "/dev/null", 0, 0), new SpawnPlan.Open(1, LOG.toString(), 0x209, 0600),
                    new SpawnPlan.Dup2(1, 2)), plan.fileActions());
            assertEquals((short) 0x440C, plan.flags());
            assertEquals(List.of(DAEMON.toString()), plan.argv());
            assertEquals(List.of(1, 2, 3, 13, 15), plan.defaultSignals());
            assertEquals(DAEMON, plan.executable());
        }

        @Test
        @DisplayName("closes every inherited descriptor from 3 with closefrom on Linux")
        void linux() {
            var plan = SpawnPlan.detachedDaemon(PosixPlatform.LINUX, DAEMON, LOG, Map.of());

            assertEquals(new SpawnPlan.CloseFrom(3), plan.fileActions().getLast());
            assertEquals(new SpawnPlan.Open(1, LOG.toString(), 0x441, 0600), plan.fileActions().get(1));
            assertTrue(PosixPlatform.LINUX.usesCloseFromAction());
            assertFalse(PosixPlatform.MACOS.usesCloseFromAction());
        }

        @Test
        @DisplayName("passes the environment sorted and withholds a job token")
        void environment() {
            var plan = SpawnPlan.detachedDaemon(PosixPlatform.LINUX, DAEMON, LOG,
                    Map.of("PM_MCP_BASE", "/b", "PM_MCP_JOB_TOKEN", "secret", "A", "x=y"));

            assertEquals(List.of("A=x=y", "PM_MCP_BASE=/b"), plan.environment());
        }

        @Test
        @DisplayName("refuses a relative executable, never looked up through PATH")
        void absoluteOnly() {
            var relative = Path.of("pm-mcpd");

            assertThrows(IllegalArgumentException.class,
                    () -> SpawnPlan.detachedDaemon(PosixPlatform.LINUX, relative, LOG, Map.of()));
        }
    }
}
