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

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

/**
 * The native calls of the launcher. {@link NativeKernel} implements them through FFM, the Linux-only
 * ones in {@link LinuxCalls}; the launch logic depends only on this interface.
 */
interface Kernel {

    /**
     * {@code prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)} (Linux).
     *
     * @throws NativeCallException if the kernel refuses it
     */
    void setNoNewPrivs() throws NativeCallException;

    /**
     * {@code landlock_create_ruleset(NULL, 0, LANDLOCK_CREATE_RULESET_VERSION)} (Linux).
     *
     * @return the highest Landlock ABI the kernel offers, {@code 0} if Landlock is unavailable
     */
    int landlockAbi();

    /**
     * Creates a ruleset handling the given filesystem rights.
     *
     * @param handledAccessFs the {@code handled_access_fs} mask
     * @return the ruleset descriptor
     * @throws NativeCallException if the call fails
     */
    int createRuleset(long handledAccessFs) throws NativeCallException;

    /**
     * Opens a path with {@code O_PATH | O_CLOEXEC}, following symbolic links.
     *
     * @param path the path
     * @return the descriptor, or empty if the path cannot be opened (missing, not reachable)
     */
    OptionalInt openPath(Path path);

    /**
     * Adds a {@code LANDLOCK_RULE_PATH_BENEATH} rule.
     *
     * @param rulesetFd     the ruleset
     * @param parentFd      the {@code O_PATH} descriptor of the rule's path
     * @param allowedAccess the granted rights
     * @throws NativeCallException if the call fails
     */
    void addPathBeneath(int rulesetFd, int parentFd, long allowedAccess) throws NativeCallException;

    /**
     * {@code landlock_restrict_self(fd, 0)}.
     *
     * @param rulesetFd the ruleset
     * @throws NativeCallException if the call fails
     */
    void restrictSelf(int rulesetFd) throws NativeCallException;

    /**
     * Closes a descriptor, ignoring errors.
     *
     * @param fd the descriptor
     */
    void close(int fd);

    /**
     * {@code setpgid(0, 0)}: the calling process becomes leader of its own process group.
     *
     * @throws NativeCallException if the call fails
     */
    void setProcessGroup() throws NativeCallException;

    /**
     * {@code execve(argv[0], argv, environ)}: replaces the process; returns only on failure.
     *
     * @param argv the absolute program path followed by its arguments
     * @return the {@code errno} of the failed call
     */
    int execve(List<String> argv);
}
