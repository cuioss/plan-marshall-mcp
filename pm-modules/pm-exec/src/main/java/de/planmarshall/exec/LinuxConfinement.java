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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Confines the launcher on Linux before {@code execve} (PM-SEC-5, doc/specification/job-runtime/
 * 02-confinement-and-environment.adoc): {@code PR_SET_NO_NEW_PRIVS}, then, with Landlock ABI 2 or
 * higher, a ruleset with the write set and the enumerated read set. ABI 1 applies no ruleset
 * ({@code partial(abi=1)}), no Landlock records {@code unavailable}.
 * <p>
 * A rule whose path cannot be opened is skipped, which only withholds access. Any other failure
 * after the ABI probe aborts the launch: a job never runs unconfined where Landlock is available.
 *
 * @param kernel the native calls
 * @param root   the filesystem root the read set is enumerated from, {@code /} in production
 */
record LinuxConfinement(Kernel kernel, Path root) {

    /**
     * Applies the confinement to the calling process.
     *
     * @param launch the launch
     * @return the applied confinement
     * @throws UsageException      if {@code --deny-read} is missing or invalid
     * @throws NativeCallException if a native call fails
     */
    Confinement apply(Command.Launch launch) throws UsageException, NativeCallException {
        var base = ReadSet.canonical(launch.deniedBase()
                .orElseThrow(() -> new UsageException(Command.DENY_READ + " is mandatory on Linux")));
        var readSet = new ArrayList<>(ReadSet.enumerate(root, base));
        readSet.addAll(launch.readPaths());

        kernel.setNoNewPrivs();
        var confinement = Confinement.forAbi(kernel.landlockAbi(), true);
        if (!confinement.isConfined()) {
            return confinement;
        }
        int abi = confinement.landlockAbi();
        int ruleset = kernel.createRuleset(Landlock.handledAccess(abi));
        try {
            addRules(ruleset, launch.writePaths(), abi, true);
            addRules(ruleset, readSet, abi, false);
            kernel.restrictSelf(ruleset);
        } finally {
            kernel.close(ruleset);
        }
        return confinement;
    }

    private void addRules(int ruleset, List<Path> paths, int abi, boolean write) throws NativeCallException {
        for (var path : paths) {
            var fd = kernel.openPath(path);
            if (fd.isEmpty()) {
                continue;
            }
            try {
                boolean directory = Files.isDirectory(path);
                long access = write ? Landlock.writeAccess(abi, directory) : Landlock.readAccess(abi, directory);
                kernel.addPathBeneath(ruleset, fd.getAsInt(), access);
            } finally {
                kernel.close(fd.getAsInt());
            }
        }
    }
}
