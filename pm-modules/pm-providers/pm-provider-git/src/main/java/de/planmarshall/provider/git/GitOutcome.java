/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.provider.git;

/**
 * The closed outcome enumeration of the git contract.
 * <p>
 * Every member except {@link #FAILED} and {@link #UNAVAILABLE} is a domain outcome of the contract;
 * {@link #FAILED} is a transport or I/O failure and {@link #UNAVAILABLE} the fail-closed result of
 * {@code git.worktree-sha}. Each operation of {@link GitOperations} documents the members it returns.
 *
 * @since 0.1
 */
public enum GitOutcome {
    /** The operation completed. */
    OK,
    /** The path is not inside a git repository. */
    NOT_A_REPOSITORY,
    /** A revision, branch or remote does not resolve. */
    UNKNOWN_REVISION,
    /** {@code commit}: nothing is staged or changed. */
    NOTHING_TO_COMMIT,
    /** {@code push}: the remote branch is not an ancestor of the pushed commit. */
    REJECTED_NON_FAST_FORWARD,
    /** {@code push}: the remote branch moved away from the expected head (lease). */
    REJECTED_LEASE,
    /** The remote URL does not match the allowed scheme and origin; nothing was sent. */
    REMOTE_ORIGIN_MISMATCH,
    /** The operation needs a capability the native variant does not serve, e.g. a repository hook. */
    CAPABILITY_MISSING,
    /** {@code worktree-add}: the target path or the branch already exists. */
    ALREADY_EXISTS,
    /** {@code worktree-add}: the branch is checked out in another worktree. */
    BRANCH_CHECKED_OUT,
    /** {@code worktree-remove}: no linked worktree at that path. */
    WORKTREE_NOT_FOUND,
    /** {@code worktree-remove}: the worktree has uncommitted or untracked changes. */
    WORKTREE_DIRTY,
    /** {@code worktree-remove}: the worktree is locked. */
    WORKTREE_LOCKED,
    /** A derived path lies outside the permitted root or is the main worktree. */
    PATH_REFUSED,
    /** {@code worktree-sha}: no digest could be produced (fail-closed). */
    UNAVAILABLE,
    /** An I/O or transport failure outside the domain outcomes. */
    FAILED
}
