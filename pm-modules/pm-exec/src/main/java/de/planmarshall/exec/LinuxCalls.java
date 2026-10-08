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

import de.planmarshall.exec.NativeKernel.Fn;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * The native calls only the Linux kernel has: {@code prctl(PR_SET_NO_NEW_PRIVS)}, the three Landlock
 * system calls and {@code open} with {@code O_PATH}. The Landlock calls go through the libc symbol
 * {@code syscall}, since glibc has no wrappers for them. {@link NativeKernel} links and invokes the
 * downcalls; no other platform can execute this class, so its tests run on Linux only.
 */
final class LinuxCalls {

    /** {@code PR_SET_NO_NEW_PRIVS} (linux/prctl.h). */
    static final int PR_SET_NO_NEW_PRIVS = 38;
    /** {@code O_PATH} on x86_64 and aarch64. */
    static final int O_PATH = 0x200000;
    /** {@code O_CLOEXEC} on x86_64 and aarch64. */
    static final int O_CLOEXEC = 0x80000;

    private final NativeKernel kernel;

    /**
     * @param kernel the kernel linking the downcalls
     */
    LinuxCalls(NativeKernel kernel) {
        this.kernel = kernel;
    }

    /**
     * {@code prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)}.
     *
     * @throws NativeCallException if the kernel refuses it
     */
    void setNoNewPrivs() throws NativeCallException {
        var handle = kernel.handle(Fn.PRCTL);
        try (var arena = Arena.ofConfined()) {
            var state = arena.allocate(NativeKernel.CALL_STATE);
            int result = NativeKernel.invoke(Fn.PRCTL.symbol,
                    () -> (int) handle.invokeExact(state, PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L));
            NativeKernel.check(Fn.PRCTL.symbol, result, state, "PR_SET_NO_NEW_PRIVS");
        }
    }

    /**
     * {@code landlock_create_ruleset(NULL, 0, LANDLOCK_CREATE_RULESET_VERSION)}.
     *
     * @return the highest Landlock ABI the kernel offers, {@code 0} if Landlock is unavailable
     */
    int landlockAbi() {
        try {
            long abi = syscall(Landlock.SYS_CREATE_RULESET, 0L, 0L, Landlock.CREATE_RULESET_VERSION,
                    "landlock_create_ruleset");
            return (int) abi;
        } catch (NativeCallException _) {
            // ENOSYS (no Landlock in the kernel) or EOPNOTSUPP (disabled at boot)
            return 0;
        }
    }

    /**
     * Creates a ruleset handling the given filesystem rights.
     *
     * @param handledAccessFs the {@code handled_access_fs} mask
     * @return the ruleset descriptor
     * @throws NativeCallException if the call fails
     */
    int createRuleset(long handledAccessFs) throws NativeCallException {
        try (var arena = Arena.ofConfined()) {
            var attr = Landlock.rulesetAttr(arena, handledAccessFs);
            return (int) syscall(Landlock.SYS_CREATE_RULESET, attr.address(), Landlock.RULESET_ATTR.byteSize(), 0L,
                    "landlock_create_ruleset");
        }
    }

    /**
     * Opens a path with {@code O_PATH | O_CLOEXEC}, following symbolic links.
     *
     * @param path the path
     * @return the descriptor, or empty if the path cannot be opened (missing, not reachable)
     */
    OptionalInt openPath(Path path) {
        try (var arena = Arena.ofConfined()) {
            var handle = kernel.handle(Fn.OPEN);
            var state = arena.allocate(NativeKernel.CALL_STATE);
            var cPath = arena.allocateFrom(path.toString());
            int fd = NativeKernel.invoke(Fn.OPEN.symbol,
                    () -> (int) handle.invokeExact(state, cPath, O_PATH | O_CLOEXEC));
            return fd < 0 ? OptionalInt.empty() : OptionalInt.of(fd);
        } catch (NativeCallException _) {
            return OptionalInt.empty();
        }
    }

    /**
     * Adds a {@code LANDLOCK_RULE_PATH_BENEATH} rule.
     *
     * @param rulesetFd     the ruleset
     * @param parentFd      the {@code O_PATH} descriptor of the rule's path
     * @param allowedAccess the granted rights
     * @throws NativeCallException if the call fails
     */
    void addPathBeneath(int rulesetFd, int parentFd, long allowedAccess) throws NativeCallException {
        try (var arena = Arena.ofConfined()) {
            var attr = Landlock.pathBeneathAttr(arena, allowedAccess, parentFd);
            syscall(Landlock.SYS_ADD_RULE, rulesetFd, Landlock.RULE_PATH_BENEATH, attr.address(), "landlock_add_rule");
        }
    }

    /**
     * {@code landlock_restrict_self(fd, 0)}.
     *
     * @param rulesetFd the ruleset
     * @throws NativeCallException if the call fails
     */
    void restrictSelf(int rulesetFd) throws NativeCallException {
        syscall(Landlock.SYS_RESTRICT_SELF, rulesetFd, 0L, 0L, "landlock_restrict_self");
    }

    /**
     * {@code syscall(number, a, b, c, 0)}. The trailing {@code 0} is the {@code flags} of
     * {@code landlock_add_rule}, which takes four arguments; the other two calls take three and ignore it.
     */
    private long syscall(long number, long a, long b, long c, String stage) throws NativeCallException {
        var handle = kernel.handle(Fn.SYSCALL);
        try (var arena = Arena.ofConfined()) {
            var state = arena.allocate(NativeKernel.CALL_STATE);
            long result = NativeKernel.invoke(stage, () -> (long) handle.invokeExact(state, number, a, b, c, 0L));
            if (result < 0) {
                int errno = NativeKernel.errno(state);
                throw new NativeCallException(stage, errno, stage + " failed with errno " + errno);
            }
            return result;
        }
    }
}
