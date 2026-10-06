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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisplayName("PosixSpawn (FFM posix_spawn)")
@EnabledOnOs({OS.MAC, OS.LINUX})
class PosixSpawnTest {

    private final PosixSpawn spawn = new PosixSpawn(PosixPlatform.current());
    private Path directory;

    @BeforeEach
    void setUp() throws IOException {
        directory = Files.createTempDirectory("pm-spawn");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(directory)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path script(String body) throws IOException {
        var script = directory.resolve("child.sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        script.toFile().setExecutable(true);
        return script;
    }

    @Test
    @DisplayName("starts a session leader with stdin from /dev/null and stdout/stderr appended to the log")
    void detached() throws Exception {
        var log = directory.resolve("daemon.log");
        Files.writeString(log, "before\n");
        var script = script("""
                echo "pgid=$(ps -o pgid= -p $$ | tr -d ' ')"
                echo "pid=$$"
                if read line; then echo "stdin=data"; else echo "stdin=eof"; fi
                echo "to-stderr" >&2
                echo "env=$PM_TEST_VALUE"
                exit 3
                """);

        var pid = spawn.spawn(SpawnPlan.detachedDaemon(PosixPlatform.current(), script, log,
                Map.of("PM_TEST_VALUE", "v", "PATH", "/usr/bin:/bin")));
        var status = spawn.waitFor(pid);

        assertEquals(3, PosixSpawn.exitCode(status));
        var lines = Files.readAllLines(log);
        assertEquals(List.of("before", "pgid=" + pid, "pid=" + pid, "stdin=eof", "to-stderr", "env=v"), lines);
        assertNotEquals(ProcessHandle.current().pid(), pid);
    }

    @Test
    @DisplayName("reports a missing executable with the error of posix_spawn")
    void missing() {
        var plan = SpawnPlan.detachedDaemon(PosixPlatform.current(), directory.resolve("absent"),
                directory.resolve("log"), Map.of());

        var e = assertThrows(NativeCallException.class, () -> spawn.spawn(plan));
        assertTrue(e.getMessage().contains("posix_spawn"));
    }

    @Test
    @DisplayName("reads the effective user id")
    void effectiveUserId() throws Exception {
        var owner = (Integer) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS);

        assertEquals(owner, spawn.effectiveUserId());
    }

    @Test
    @DisplayName("refuses to wait for a process that is no child")
    void noChild() {
        assertThrows(NativeCallException.class, () -> spawn.waitFor(1));
    }

    @Test
    @DisplayName("decodes wait statuses")
    void exitCodes() {
        assertEquals(0, PosixSpawn.exitCode(0));
        assertEquals(77, PosixSpawn.exitCode(77 << 8));
        assertEquals(128 + 15, PosixSpawn.exitCode(15));
    }
}
