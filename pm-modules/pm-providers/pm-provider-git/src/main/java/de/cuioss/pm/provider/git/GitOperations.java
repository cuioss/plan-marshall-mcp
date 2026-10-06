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

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The git contract: one method per operation, typed inputs, results with the closed
 * {@link GitOutcome}. Both variants (native, CLI) serve the same contract; the project's integration
 * variant selects the implementation.
 *
 * @since 0.1
 */
public interface GitOperations {

    /**
     * Opens the repository whose working tree contains {@code root} (main or linked worktree).
     *
     * @param root the worktree root
     * @return {@code OK} with the repository facts, {@code NOT_A_REPOSITORY}, or {@code FAILED}
     */
    GitResult<RepositoryInfo> open(Path root);

    /**
     * {@code git.worktree-sha}: the variant-neutral working-tree digest (job runtime specification,
     * Working-Tree Hash, version {@value WorktreeSha#CURRENT_VERSION}).
     *
     * @param root the worktree root
     * @return {@code OK} with the digest, or {@code UNAVAILABLE} naming the failing step
     */
    GitResult<WorktreeSha> worktreeSha(Path root);

    /**
     * {@code git.worktree-add}: creates a linked worktree.
     *
     * @param input the input
     * @return {@code OK}, {@code ALREADY_EXISTS}, {@code BRANCH_CHECKED_OUT}, {@code UNKNOWN_REVISION},
     *         {@code PATH_REFUSED}, {@code CAPABILITY_MISSING}, {@code NOT_A_REPOSITORY} or {@code FAILED}
     */
    GitResult<Worktree> worktreeAdd(WorktreeAddInput input);

    /**
     * {@code git.worktree-list}: the main worktree first, then the linked worktrees by name.
     *
     * @param root any worktree root of the repository
     * @return {@code OK}, {@code NOT_A_REPOSITORY} or {@code FAILED}
     */
    GitResult<List<Worktree>> worktreeList(Path root);

    /**
     * {@code git.worktree-remove}: removes a clean, unlocked linked worktree; never forced.
     *
     * @param root     the main worktree root
     * @param worktree the linked worktree to remove
     * @return {@code OK}, {@code WORKTREE_NOT_FOUND}, {@code WORKTREE_DIRTY}, {@code WORKTREE_LOCKED},
     *         {@code PATH_REFUSED}, {@code NOT_A_REPOSITORY} or {@code FAILED}
     */
    GitResult<Path> worktreeRemove(Path root, Path worktree);

    /**
     * {@code git.commit}: stages every change of the worktree (additions, modifications, deletions)
     * and commits it without signing.
     *
     * @param input the input
     * @return {@code OK} with the commit, {@code NOTHING_TO_COMMIT}, {@code CAPABILITY_MISSING},
     *         {@code NOT_A_REPOSITORY} or {@code FAILED}
     */
    GitResult<CommitInfo> commit(CommitInput input);

    /**
     * {@code git.log}: the history of a revision, newest first.
     *
     * @param root     the worktree root
     * @param revision the start revision, e.g. {@code HEAD}
     * @param maxCount the maximum number of commits, at least 1
     * @return {@code OK}, {@code UNKNOWN_REVISION}, {@code NOT_A_REPOSITORY} or {@code FAILED}
     */
    GitResult<List<CommitInfo>> log(Path root, String revision, int maxCount);

    /**
     * {@code git.fetch} from a configured remote.
     *
     * @param input the input
     * @return {@code OK} with the updated tracking refs, {@code REMOTE_ORIGIN_MISMATCH},
     *         {@code UNKNOWN_REVISION} (unknown remote), {@code CAPABILITY_MISSING}, or {@code FAILED}
     */
    GitResult<List<RefUpdate>> fetch(RemoteInput input);

    /**
     * {@code git.push} of one ref to a configured remote, optionally bound to the expected remote head.
     *
     * @param input the input
     * @return {@code OK}, {@code REJECTED_NON_FAST_FORWARD}, {@code REJECTED_LEASE},
     *         {@code REMOTE_ORIGIN_MISMATCH}, {@code UNKNOWN_REVISION}, {@code CAPABILITY_MISSING}, or
     *         {@code FAILED}
     */
    GitResult<RefUpdate> push(PushInput input);

    /**
     * Facts of an opened repository.
     *
     * @param worktreeRoot the canonical worktree root
     * @param gitDir       the canonical git directory of this worktree
     * @param commonDir    the canonical common git directory
     * @param head         the {@code HEAD} commit id, empty for an unborn branch
     * @param branch       the checked-out branch, empty when detached
     * @param linked       whether this is a linked worktree
     */
    record RepositoryInfo(Path worktreeRoot, Path gitDir, Path commonDir, Optional<String> head, Optional<String> branch,
    boolean linked) {
    }

    /**
     * The working-tree digest.
     *
     * @param digest  lower-case hex SHA-256
     * @param version the hash version
     */
    record WorktreeSha(String digest, int version) {

        /** The current hash version: inputs of rule 1 with the framing of rule 2. */
        public static final int CURRENT_VERSION = 1;
    }

    /**
     * A worktree of the repository.
     *
     * @param path     the worktree root
     * @param name     the administrative name, empty for the main worktree
     * @param branch   the checked-out branch, empty when detached
     * @param head     the {@code HEAD} commit id, empty when unborn
     * @param main     whether this is the main worktree
     * @param locked   whether the linked worktree is locked
     * @param prunable whether the linked worktree's directory is gone
     */
    record Worktree(Path path, Optional<String> name, Optional<String> branch, Optional<String> head, boolean main,
    boolean locked, boolean prunable) {
    }

    /**
     * Input of {@code git.worktree-add}.
     *
     * @param root         the main worktree root (the enrolled project root)
     * @param path         the new worktree root; must lie inside {@code root} and not exist or be empty
     * @param branch       the branch to check out; empty for a detached worktree
     * @param createBranch whether to create {@code branch} at {@code startPoint}
     * @param startPoint   the commit-ish to start from (ignored for an existing branch)
     */
    record WorktreeAddInput(Path root, Path path, Optional<String> branch, boolean createBranch, String startPoint) {

        /**
         * @param root         the root
         * @param path         the path
         * @param branch       the branch
         * @param createBranch whether to create the branch
         * @param startPoint   the start point
         */
        public WorktreeAddInput {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(startPoint, "startPoint");
        }
    }

    /**
     * A person of a commit.
     *
     * @param name  the name
     * @param email the email
     */
    record Identity(String name, String email) {
    }

    /**
     * Input of {@code git.commit}.
     *
     * @param root      the worktree root
     * @param message   the full commit message (in memory, never a file)
     * @param author    the author (the operator's commit identity)
     * @param committer the committer (the agent identity)
     */
    record CommitInput(Path root, String message, Identity author, Identity committer) {
    }

    /**
     * A commit.
     *
     * @param id            the commit id
     * @param parents       the parent ids
     * @param author        the author
     * @param authoredAt    the author time
     * @param committer     the committer
     * @param committedAt   the commit time
     * @param message       the full message
     */
    record CommitInfo(String id, List<String> parents, Identity author, Instant authoredAt, Identity committer,
    Instant committedAt, String message) {
    }

    /**
     * Credential for the HTTPS transport, e.g. {@code x-access-token} and an installation token.
     *
     * @param username the user name
     * @param secret   the secret
     */
    record GitCredential(String username, char[] secret) {

        @Override
        public boolean equals(Object other) {
            return other instanceof GitCredential that && Objects.equals(username, that.username)
                    && Arrays.equals(secret, that.secret);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(username) + Arrays.hashCode(secret);
        }

        @Override
        public String toString() {
            return "GitCredential[" + username + ", ***]";
        }
    }

    /**
     * Input of {@code git.fetch}.
     *
     * @param root       the worktree root
     * @param remote     the configured remote name
     * @param refSpecs   the ref specs; empty for the remote's configured specs
     * @param credential the transport credential, if any
     */
    record RemoteInput(Path root, String remote, List<String> refSpecs, Optional<GitCredential> credential) {
    }

    /**
     * Input of {@code git.push}.
     *
     * @param root               the worktree root
     * @param remote             the configured remote name
     * @param localRef           the local ref or commit-ish to push
     * @param remoteRef          the full remote ref, e.g. {@code refs/heads/feature}
     * @param expectedRemoteHead the expected current remote head (lease); empty for a plain push
     * @param credential         the transport credential, if any
     */
    record PushInput(Path root, String remote, String localRef, String remoteRef, Optional<String> expectedRemoteHead,
    Optional<GitCredential> credential) {
    }

    /**
     * A ref changed by a transport operation.
     *
     * @param ref    the (local tracking or remote) ref name
     * @param oldId  the previous id, empty when created
     * @param newId  the new id, empty when deleted
     * @param status the provider-neutral status, e.g. {@code updated}, {@code up_to_date}
     */
    record RefUpdate(String ref, Optional<String> oldId, Optional<String> newId, String status) {
    }
}
