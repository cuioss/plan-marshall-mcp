/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.ProcessResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("RefusingFS")
class RefusingFSTest {

    private final RefusingFS fs = new RefusingFS();

    @Test
    @DisplayName("refuses every process entry point")
    void refusesProcesses() {
        var builder = new ProcessBuilder("touch", "x");
        var out = new ByteArrayOutputStream();

        var shell = assertThrows(RefusingFS.CapabilityRefusedException.class, () -> fs.runInShell("echo", new String[0]));
        assertThrows(RefusingFS.CapabilityRefusedException.class, () -> fs.runProcess(builder, out, out, "in"));
        assertThrows(RefusingFS.CapabilityRefusedException.class,
                () -> fs.runProcess(builder, out, out, new ByteArrayInputStream(new byte[0])));
        assertThrows(RefusingFS.CapabilityRefusedException.class,
                () -> fs.execute(builder, new ByteArrayInputStream(new byte[0])));

        assertEquals("process_execution", shell.getReason());
        assertTrue(shell.getMessage().startsWith("capability_missing"));
    }

    @Test
    @DisplayName("finds no git executable and stays refusing when copied")
    void noGitExecutable() {
        assertNull(fs.discoverGitExe());
        assertInstanceOf(RefusingFS.class, fs.newInstance());
    }

    @Test
    @DisplayName("reports an absent hook as not present on both entry points")
    void absentHook(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setFs(fs).call()) {
            Repository repository = git.getRepository();
            var out = new ByteArrayOutputStream();

            ProcessResult viaPublic = fs.runHookIfPresent(repository, "pre-commit", new String[0], out, out, null);
            ProcessResult viaInternal = fs.internalRunHookIfPresent(repository, "pre-commit", new String[0], out, out,
                    null);

            assertEquals(ProcessResult.Status.NOT_PRESENT, viaPublic.getStatus());
            assertEquals(ProcessResult.Status.NOT_PRESENT, viaInternal.getStatus());
            assertFalse(viaPublic.isExecutedWithError());
        }
    }
}
