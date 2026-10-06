/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The FFM calls that are safe inside the test JVM. Calls that change the process for good
 * ({@code setpgid}, {@code landlock_restrict_self}, a successful {@code execve}) run in
 * {@code PmExecIT} against a child process.
 */
@DisplayName("NativeKernel: FFM calls in the test JVM")
class NativeKernelTest {

    private final NativeKernel kernel = new NativeKernel(Os.of(System.getProperty("os.name")));

    @Test
    @DisplayName("execve of a missing program returns ENOENT and keeps the process")
    void execveMissing() {
        assertEquals(2, kernel.execve(List.of("/nonexistent/pm-exec-test-program")));
    }

    @Test
    @DisplayName("close of an invalid descriptor is ignored")
    void closeInvalid() {
        assertDoesNotThrow(() -> kernel.close(-1));
    }

    @Test
    @DisplayName("PmExec.run: --probe prints the platform JSON, a usage error exits 64")
    void pmExecRun() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        var errStream = new PrintStream(err, true, StandardCharsets.UTF_8);

        assertEquals(0, PmExec.run(new String[]{"--probe"}, outStream, errStream));
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("{\"os\":"));
        assertEquals(Launcher.EXIT_USAGE, PmExec.run(new String[]{"--", "relative"}, outStream, errStream));
    }

    @Nested
    @EnabledOnOs(OS.MAC)
    @DisplayName("macOS")
    class Mac {

        @Test
        @DisplayName("Linux-only symbols are reported as a failed native call, never linked")
        void noPrctl() {
            var e = assertThrows(NativeCallException.class, kernel::setNoNewPrivs);

            assertEquals("prctl", e.stage());
            assertTrue(e.getMessage().contains("symbol not found"));
        }
    }

    @Nested
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Linux")
    class Linux {

        @Test
        @DisplayName("opens an existing path with O_PATH and refuses a missing one")
        void openPath() {
            var fd = kernel.openPath(Path.of("/"));

            assertTrue(fd.isPresent());
            kernel.close(fd.getAsInt());
            assertTrue(kernel.openPath(Path.of("/nonexistent/pm-exec")).isEmpty());
        }

        @Test
        @DisplayName("probes the Landlock ABI and creates a ruleset where ABI >= 2")
        void ruleset() throws Exception {
            int abi = kernel.landlockAbi();
            assumeTrue(abi >= 2, "Landlock ABI >= 2 needed, kernel offers " + abi);

            int fd = kernel.createRuleset(Landlock.handledAccess(abi));
            assertTrue(fd >= 0);
            var dir = kernel.openPath(Path.of("/usr"));
            assertTrue(dir.isPresent());
            kernel.addPathBeneath(fd, dir.getAsInt(), Landlock.readAccess(abi, true));
            kernel.close(dir.getAsInt());
            kernel.close(fd);
        }

        @Test
        @DisplayName("sets no-new-privileges")
        void noNewPrivs() throws Exception {
            kernel.setNoNewPrivs();

            assertTrue(Files.readAllLines(Path.of("/proc/thread-self/status")).contains("NoNewPrivs:\t1"));
        }
    }
}
