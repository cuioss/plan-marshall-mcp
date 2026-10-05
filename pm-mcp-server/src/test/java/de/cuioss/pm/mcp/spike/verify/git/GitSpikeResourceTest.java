/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.verify.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Spike endpoint /api/v1/spike/git (resource logic)")
class GitSpikeResourceTest {

    private final GitSpikeResource resource = new GitSpikeResource();

    private static String outcome(Map<?, ?> steps, String step) {
        return String.valueOf(((Map<?, ?>) steps.get(step)).get("outcome"));
    }

    @Test
    @DisplayName("runs the JGit scenario with the expected outcome per step")
    void scenario(@TempDir Path dir) {
        Path repo = dir.resolve("repo");

        var response = resource.run(new GitSpikeResource.Request("scenario", repo.toString()));
        var steps = (Map<?, ?>) ((Map<?, ?>) response.getEntity()).get("steps");

        assertEquals(200, response.getStatus());
        for (String step : new String[]{"init", "commit", "worktree-sha", "log", "worktree-add", "worktree-commit",
                "worktree-list", "worktree-remove", "push", "fetch"}) {
            assertEquals("OK", outcome(steps, step), step + ": " + steps.get(step));
        }
        assertEquals("CAPABILITY_MISSING", outcome(steps, "commit-with-hook"));

        var read = resource.run(new GitSpikeResource.Request("worktree-list", repo.toString()));
        assertEquals("OK", outcome((Map<?, ?>) ((Map<?, ?>) read.getEntity()).get("steps"), "worktree-list"));
    }

    @Test
    @DisplayName("answers read operations and refuses unknown ones")
    void reads(@TempDir Path dir) {
        assertEquals("NOT_A_REPOSITORY", outcome((Map<?, ?>) ((Map<?, ?>) resource
                .run(new GitSpikeResource.Request("open", dir.toString())).getEntity()).get("steps"), "open"));
        assertEquals(200, resource.run(new GitSpikeResource.Request("log", dir.toString())).getStatus());
        assertEquals(200, resource.run(new GitSpikeResource.Request("worktree-sha", dir.toString())).getStatus());
        assertEquals(400, resource.run(new GitSpikeResource.Request("rm-rf", dir.toString())).getStatus());
        assertEquals(400, resource.run(new GitSpikeResource.Request(null, null)).getStatus());
    }
}
