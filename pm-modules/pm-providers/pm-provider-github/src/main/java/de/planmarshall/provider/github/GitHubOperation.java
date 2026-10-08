/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.provider.github;

import java.util.Map;

/**
 * The operation classes of the native GitHub variant, each with the narrowest repository permission set an
 * installation token is minted with for it ({@link InstallationTokens#token(long, String, GitHubOperation)}).
 * <p>
 * A comment on a pull request travels through the issue-comment endpoint
 * ({@code POST /repos/{owner}/{repo}/issues/{number}/comments}) and still needs {@code pull_requests: write}:
 * GitHub answers a token narrowed to {@code issues: write} alone with {@code 403} there.
 *
 * @since 0.1
 */
public enum GitHubOperation {

    /** Listing the review threads of a pull request. */
    REVIEW_THREADS_READ(Map.of(Permission.PULL_REQUESTS, Permission.READ)),
    /** Resolving a review thread. */
    REVIEW_THREAD_RESOLVE(Map.of(Permission.PULL_REQUESTS, Permission.WRITE)),
    /** Posting a comment on a pull request. */
    PULL_REQUEST_COMMENT(Map.of(Permission.PULL_REQUESTS, Permission.WRITE)),
    /** Posting a comment on an issue that is no pull request. */
    ISSUE_COMMENT(Map.of(Permission.ISSUES, Permission.WRITE)),
    /** Adding a pull request to the merge queue. */
    MERGE_QUEUE_ENQUEUE(Map.of(Permission.CONTENTS, Permission.WRITE, Permission.PULL_REQUESTS, Permission.WRITE));

    private final Map<String, String> permissions;

    GitHubOperation(Map<String, String> permissions) {
        this.permissions = permissions;
    }

    /**
     * @return the permission set of the installation token, permission name to access level
     */
    public Map<String, String> permissions() {
        return permissions;
    }

    /** Names and access levels of GitHub App repository permissions. */
    private static final class Permission {

        static final String PULL_REQUESTS = "pull_requests";
        static final String ISSUES = "issues";
        static final String CONTENTS = "contents";
        static final String READ = "read";
        static final String WRITE = "write";

        private Permission() {
        }
    }
}
