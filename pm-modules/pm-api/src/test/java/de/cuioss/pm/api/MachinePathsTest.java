/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("MachinePaths")
class MachinePathsTest {

    private static final Path HOME = Path.of("/home/user");

    @Nested
    @DisplayName("Resolution of PM_MCP_BASE")
    class Resolution {

        @Test
        @DisplayName("defaults to ~/.plan-marshall-mcp")
        void defaultsBelowHome() {
            var paths = MachinePaths.resolve(Map.of(), HOME, MachinePaths.Os.LINUX);

            assertEquals(Path.of("/home/user/.plan-marshall-mcp"), paths.base());
        }

        @Test
        @DisplayName("takes the environment variable when set")
        void takesEnvironment() {
            var paths = MachinePaths.resolve(Map.of("PM_MCP_BASE", "/srv/pm/../pm-base"), HOME, MachinePaths.Os.LINUX);

            assertEquals(Path.of("/srv/pm-base"), paths.base());
        }

        @Test
        @DisplayName("ignores a blank environment variable")
        void ignoresBlank() {
            var paths = MachinePaths.resolve(Map.of("PM_MCP_BASE", " "), HOME, MachinePaths.Os.MACOS);

            assertEquals(Path.of("/home/user/.plan-marshall-mcp"), paths.base());
        }

        @Test
        @DisplayName("resolves the running process")
        void resolvesCurrent() {
            assertTrue(MachinePaths.current().base().isAbsolute());
        }
    }

    @Test
    @DisplayName("derives every runtime path below the base")
    void derivesPaths() {
        var paths = new MachinePaths(Path.of("/b"), MachinePaths.Os.LINUX);

        assertEquals(Path.of("/b/run/runtime.sock"), paths.socket());
        assertEquals(Path.of("/b/run/runtime.token"), paths.runtimeToken());
        assertEquals(Path.of("/b/state/runtime.json"), paths.runtimeRecord());
        assertEquals(Path.of("/b/locks/runtime.lock"), paths.runtimeLock());
        assertEquals(Path.of("/b/locks/runtime-start.lock"), paths.runtimeStartLock());
        assertEquals(Path.of("/b/logs/daemon.log"), paths.daemonLog());
    }

    @Nested
    @DisplayName("sun_path limit")
    class SunPath {

        /** The socket suffix "/run/runtime.sock" is 17 bytes; the NUL makes the limit inclusive. */
        @ParameterizedTest(name = "{0}: base of {1} bytes fits = {2}")
        @CsvSource({"MACOS, 86, true", "MACOS, 87, false", "LINUX, 90, true", "LINUX, 91, false"})
        void checksLimit(MachinePaths.Os os, int baseBytes, boolean fits) {
            var base = "/" + "a".repeat(baseBytes - 1);
            var paths = new MachinePaths(Path.of(base), os);

            assertEquals(baseBytes + 17, paths.socketPathBytes());
            assertEquals(fits, paths.socketPathFits());
        }

        @Test
        @DisplayName("counts UTF-8 bytes, not characters")
        void countsBytes() {
            var paths = new MachinePaths(Path.of("/" + "ä".repeat(43)), MachinePaths.Os.MACOS);

            assertFalse(paths.socketPathFits());
        }
    }

    @Nested
    @DisplayName("Operating system")
    class OperatingSystem {

        @Test
        @DisplayName("maps os.name values")
        void mapsNames() {
            assertEquals(MachinePaths.Os.MACOS, MachinePaths.Os.of("Mac OS X"));
            assertEquals(MachinePaths.Os.LINUX, MachinePaths.Os.of("Linux"));
            assertEquals(104, MachinePaths.Os.MACOS.sunPathLimit());
            assertEquals(108, MachinePaths.Os.LINUX.sunPathLimit());
        }

        @Test
        @DisplayName("refuses an unsupported platform")
        void refusesUnsupported() {
            assertThrows(IllegalStateException.class, () -> MachinePaths.Os.of("Windows 11"));
        }
    }
}
