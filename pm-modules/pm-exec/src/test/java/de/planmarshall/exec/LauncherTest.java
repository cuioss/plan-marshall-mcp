/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Launcher: probe and launch")
class LauncherTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @TempDir
    Path temp;

    private Launcher launcher(Os os, FakeKernel kernel) throws IOException {
        return new Launcher(os, kernel, temp.toRealPath(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8).strip();
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8).strip();
    }

    @Nested
    @DisplayName("--probe")
    class Probe {

        @Test
        @DisplayName("macOS: no Landlock, no no-new-privileges, no native call")
        void macos() throws Exception {
            var kernel = new FakeKernel(5);

            int exit = launcher(Os.MACOS, kernel).run(List.of("--probe"));

            assertEquals(0, exit);
            assertEquals("{\"os\":\"macos\",\"landlock_abi\":0,\"no_new_privs\":false,\"confinement\":\"unavailable\"}",
                    out());
            assertTrue(kernel.calls.isEmpty());
        }

        @Test
        @DisplayName("Linux with ABI 4")
        void linux() throws Exception {
            int exit = launcher(Os.LINUX, new FakeKernel(4)).run(List.of("--probe"));

            assertEquals(0, exit);
            assertEquals("{\"os\":\"linux\",\"landlock_abi\":4,\"no_new_privs\":true,\"confinement\":\"confined\"}",
                    out());
        }

        @Test
        @DisplayName("Linux refusing no-new-privileges, ABI 1")
        void linuxPartial() throws Exception {
            var kernel = new FakeKernel(1);
            kernel.refuseNoNewPrivs = true;

            launcher(Os.LINUX, kernel).run(List.of("--probe"));

            assertEquals(
                    "{\"os\":\"linux\",\"landlock_abi\":1,\"no_new_privs\":false,\"confinement\":\"partial(abi=1)\"}",
                    out());
        }
    }

    @Nested
    @DisplayName("launch")
    class Launch {

        @Test
        @DisplayName("macOS: process group, report line, execve with the argument vector")
        void macos() throws Exception {
            var kernel = new FakeKernel(0);

            int exit = launcher(Os.MACOS, kernel).run(List.of("--", "/bin/echo", "a b"));

            assertEquals(Launcher.EXIT_EXEC, exit);
            assertEquals(List.of("setpgid", "execve"), kernel.calls);
            assertEquals(List.of("/bin/echo", "a b"), kernel.executed);
            var lines = err().lines().toList();
            assertEquals(2, lines.size());
            assertTrue(lines.getFirst().startsWith("{\"pm_exec\":\"launched\",\"pid\":" + ProcessHandle.current().pid()
                    + ",\"process_started_at\":\""), lines.getFirst());
            assertTrue(lines.getFirst().endsWith(
                    ",\"confinement\":\"unavailable\",\"landlock_abi\":null,\"no_new_privs\":false}"), lines.getFirst());
            assertEquals("{\"pm_exec\":\"error\",\"stage\":\"execve\",\"errno\":2,"
                    + "\"message\":\"cannot execute /bin/echo\"}", lines.get(1));
        }

        @Test
        @DisplayName("Linux: confinement before the process group, reported with its ABI")
        void linux() throws Exception {
            var base = Files.createDirectories(temp.toRealPath().resolve("base"));
            var kernel = new FakeKernel(3);

            launcher(Os.LINUX, kernel).run(List.of("--deny-read", base.toString(), "--", "/bin/true"));

            assertEquals(List.of("prctl", "abi", "create:7fff", "restrict:100", "setpgid", "execve"), kernel.calls);
            assertTrue(err().lines().findFirst().orElseThrow()
                    .endsWith(",\"confinement\":\"confined\",\"landlock_abi\":3,\"no_new_privs\":true}"));
        }

        @Test
        @DisplayName("usage error: exit 64, nothing executed")
        void usage() throws Exception {
            var kernel = new FakeKernel(3);

            int exit = launcher(Os.MACOS, kernel).run(List.of("--", "bin/echo"));

            assertEquals(Launcher.EXIT_USAGE, exit);
            assertTrue(kernel.calls.isEmpty());
            assertTrue(err().startsWith("{\"pm_exec\":\"error\",\"stage\":\"usage\",\"errno\":0,"));
        }

        @Test
        @DisplayName("refused process group: exit 126, nothing executed")
        void processGroupRefused() throws Exception {
            var kernel = new FakeKernel(0);
            kernel.refuseProcessGroup = true;

            int exit = launcher(Os.MACOS, kernel).run(List.of("--", "/bin/true"));

            assertEquals(Launcher.EXIT_SETUP, exit);
            assertEquals(List.of("setpgid"), kernel.calls);
            assertEquals("{\"pm_exec\":\"error\",\"stage\":\"setpgid\",\"errno\":1,\"message\":\"refused\"}", err());
        }
    }

    @Test
    @DisplayName("Json.quote escapes quotes, backslashes and control characters")
    void quote() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\u0001\"", Json.quote("a\"b\\c\n\r\t\u0001"));
    }

    @Test
    @DisplayName("Os.of maps os.name")
    void os() {
        assertEquals(Os.LINUX, Os.of("Linux"));
        assertEquals(Os.MACOS, Os.of("Mac OS X"));
    }
}
