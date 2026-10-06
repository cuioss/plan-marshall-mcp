/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("GitHubOperation: permission set per operation class")
class GitHubOperationTest {

    @Test
    @DisplayName("requests pull_requests:write for a pull-request comment, not issues:write")
    void pullRequestCommentNeedsPullRequestsWrite() {
        var permissions = GitHubOperation.PULL_REQUEST_COMMENT.permissions();

        assertEquals(Map.of("pull_requests", "write"), permissions);
        assertFalse(permissions.containsKey("issues"));
    }

    @Test
    @DisplayName("maps every operation class to its narrowest permission set")
    void mapsOperations() {
        assertEquals(Map.of("pull_requests", "read"), GitHubOperation.REVIEW_THREADS_READ.permissions());
        assertEquals(Map.of("pull_requests", "write"), GitHubOperation.REVIEW_THREAD_RESOLVE.permissions());
        assertEquals(Map.of("issues", "write"), GitHubOperation.ISSUE_COMMENT.permissions());
        assertEquals(Map.of("contents", "write", "pull_requests", "write"),
                GitHubOperation.MERGE_QUEUE_ENQUEUE.permissions());
    }

    @ParameterizedTest
    @EnumSource(GitHubOperation.class)
    @DisplayName("never asks for an administrative permission and exposes an immutable set")
    void staysNarrow(GitHubOperation operation) {
        var permissions = operation.permissions();

        assertFalse(permissions.isEmpty());
        assertFalse(permissions.containsKey("administration"));
        assertThrows(UnsupportedOperationException.class, () -> permissions.put("administration", "write"));
    }
}
