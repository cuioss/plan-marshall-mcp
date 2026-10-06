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

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;

import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.ProcessResult;

/**
 * A JGit file system that never starts a process.
 * <p>
 * Every process entry point of JGit's {@link FS} is refused with
 * {@link CapabilityRefusedException} ({@code capability_missing}): repository hooks that are present,
 * shell commands (filter drivers, external diff), and process execution in general. The discovery
 * of the {@code git} executable, which JGit uses to locate the system configuration by running
 * {@code git}, reports none, so the system configuration is never read through a process.
 * An absent hook is reported as not present, as JGit does, so operations of a repository without
 * hooks run unchanged.
 *
 * @since 0.1
 */
public class RefusingFS extends FS_POSIX {

    /** Creates the refusing file system. */
    public RefusingFS() {
    }

    private RefusingFS(FS src) {
        super(src);
    }

    @Override
    public FS newInstance() {
        return new RefusingFS(this);
    }

    @Override
    protected File discoverGitExe() {
        return null;
    }

    @Override
    public ProcessResult runHookIfPresent(Repository repository, String hookName, String[] args, OutputStream outRedirect,
            OutputStream errRedirect, String stdinArgs) {
        return refuseHook(repository, hookName);
    }

    @Override
    protected ProcessResult internalRunHookIfPresent(Repository repository, String hookName, String[] args,
            OutputStream outRedirect, OutputStream errRedirect, String stdinArgs) {
        return refuseHook(repository, hookName);
    }

    private ProcessResult refuseHook(Repository repository, String hookName) {
        if (findHook(repository, hookName) == null) {
            return new ProcessResult(ProcessResult.Status.NOT_PRESENT);
        }
        throw new CapabilityRefusedException("repository_hook", hookName);
    }

    @Override
    public ProcessBuilder runInShell(String cmd, String[] args) {
        throw new CapabilityRefusedException("process_execution", "shell command");
    }

    @Override
    public int runProcess(ProcessBuilder processBuilder, OutputStream outRedirect, OutputStream errRedirect,
            String stdinArgs) {
        throw new CapabilityRefusedException("process_execution", String.valueOf(processBuilder.command()));
    }

    @Override
    public int runProcess(ProcessBuilder processBuilder, OutputStream outRedirect, OutputStream errRedirect,
            InputStream inRedirect) {
        throw new CapabilityRefusedException("process_execution", String.valueOf(processBuilder.command()));
    }

    @Override
    public ExecutionResult execute(ProcessBuilder pb, InputStream in) {
        throw new CapabilityRefusedException("process_execution", String.valueOf(pb.command()));
    }

    /**
     * Raised when an operation would start a process; maps to the outcome
     * {@link GitOutcome#CAPABILITY_MISSING}.
     */
    public static final class CapabilityRefusedException extends JGitInternalException {

        @Serial
        private static final long serialVersionUID = 1L;

        /** The missing capability, e.g. {@code repository_hook}. */
        private final String reason;

        /**
         * @param reason the missing capability
         * @param detail what would have been run
         */
        public CapabilityRefusedException(String reason, String detail) {
            super("capability_missing{reason: " + reason + "}: " + detail);
            this.reason = reason;
        }

        /**
         * @return the missing capability
         */
        public String getReason() {
            return reason;
        }
    }
}
