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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The Linux-only FFM calls that are safe inside the test JVM, reached through {@link NativeKernel} as
 * the launcher reaches them. {@code landlock_restrict_self} changes the process for good and runs in
 * {@code PmExecIT} against a child process.
 */
@EnabledOnOs(OS.LINUX)
@DisplayName("LinuxCalls: no-new-privileges, O_PATH and Landlock in the test JVM")
class LinuxCallsTest {

    private final Kernel kernel = new NativeKernel(Os.LINUX);

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
