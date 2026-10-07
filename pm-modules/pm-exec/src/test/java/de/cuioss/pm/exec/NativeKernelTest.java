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

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The FFM calls of every platform that are safe inside the test JVM, and the downcall plumbing. Calls
 * that change the process for good ({@code setpgid}, a successful {@code execve}) run in
 * {@code PmExecIT} against a child process; the Linux-only calls are covered by
 * {@link LinuxCallsTest}.
 */
@DisplayName("NativeKernel: plumbing and the calls of every platform in the test JVM")
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
    @DisplayName("downcall plumbing")
    class Plumbing {

        @Test
        @DisplayName("a zero result passes, a non-zero result fails with the captured errno")
        void check() {
            try (var arena = Arena.ofConfined()) {
                var state = arena.allocate(NativeKernel.CALL_STATE);
                state.set(JAVA_INT, NativeKernel.CALL_STATE.byteOffset(PathElement.groupElement("errno")), 13);

                assertEquals(13, NativeKernel.errno(state));
                assertDoesNotThrow(() -> NativeKernel.check("setpgid", 0, state, "setpgid(0, 0)"));
                var e = assertThrows(NativeCallException.class,
                        () -> NativeKernel.check("setpgid", -1, state, "setpgid(0, 0)"));
                assertEquals("setpgid", e.stage());
                assertEquals(13, e.errno());
                assertEquals("setpgid(0, 0) failed with errno 13", e.getMessage());
            }
        }

        @Test
        @DisplayName("a downcall returns its value")
        void invokeReturns() throws Exception {
            assertEquals(7, NativeKernel.<Integer>invoke("close", () -> 7));
        }

        @Test
        @DisplayName("a downcall that cannot be made is a failed native call without errno")
        void invokeFails() {
            var e = assertThrows(NativeCallException.class, () -> NativeKernel.invoke("close", () -> {
                throw new IllegalStateException("no handle");
            }));

            assertEquals("close", e.stage());
            assertEquals(0, e.errno());
            assertTrue(e.getMessage().startsWith("close could not be called: "), e.getMessage());
        }

        @Test
        @DisplayName("an Error of a downcall is rethrown unchanged")
        void invokeRethrowsError() {
            var error = new AssertionError("fatal");

            assertSame(error, assertThrows(AssertionError.class, () -> NativeKernel.invoke("close", () -> {
                throw error;
            })));
        }

        @Test
        @DisplayName("a handle is linked once and reused")
        void handleCached() throws Exception {
            assertSame(kernel.handle(NativeKernel.Fn.CLOSE), kernel.handle(NativeKernel.Fn.CLOSE));
        }
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
}
