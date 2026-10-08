/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.relay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import lombok.experimental.UtilityClass;

/**
 * Resolves {@code PM-MCP-Workspace}: the canonical git top-level of the working directory, or the
 * working directory when it lies in no repository. The top-level is the nearest directory holding a
 * {@code .git} entry (a directory, or the file of a linked worktree), found without starting
 * {@code git}, which the start budget of the relay does not allow.
 */
@UtilityClass
class Workspace {

    /**
     * @param workingDirectory the working directory
     * @return the workspace path
     * @throws IOException if the working directory cannot be resolved
     */
    static Path resolve(Path workingDirectory) throws IOException {
        var canonical = workingDirectory.toRealPath();
        for (var directory = canonical; directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return directory;
            }
        }
        return canonical;
    }
}
