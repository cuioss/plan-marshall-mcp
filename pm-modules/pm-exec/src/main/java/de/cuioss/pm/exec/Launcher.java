/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.exec;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Runs one {@code pm-exec} invocation: the probe, or a launch that ends in {@code execve}.
 *
 * @param os     the operating system
 * @param kernel the native calls
 * @param root   the filesystem root of the read set enumeration
 * @param out    {@code stdout}, for the probe
 * @param err    {@code stderr}, for the launch report and errors
 */
record Launcher(Os os, Kernel kernel, Path root, PrintStream out, PrintStream err) {

    /** Exit code for an argument vector outside the protocol ({@code EX_USAGE}). */
    static final int EXIT_USAGE = 64;
    /** Exit code when confinement or the process group could not be applied. */
    static final int EXIT_SETUP = 126;
    /** Exit code when {@code execve} failed. */
    static final int EXIT_EXEC = 127;

    /**
     * Runs the invocation. Returns only if no {@code execve} replaced the process.
     *
     * @param args the arguments without the binary name
     * @return the exit code
     */
    int run(List<String> args) {
        try {
            return switch (Command.parse(args)) {
                case Command.Probe _ -> probe();
                case Command.Launch launch -> launch(launch);
            };
        } catch (UsageException e) {
            return error("usage", 0, e.getMessage(), EXIT_USAGE);
        } catch (NativeCallException e) {
            return error(e.stage(), e.errno(), e.getMessage(), EXIT_SETUP);
        }
    }

    private int probe() {
        int abi = 0;
        boolean noNewPrivs = false;
        if (os == Os.LINUX) {
            try {
                kernel.setNoNewPrivs();
                noNewPrivs = true;
            } catch (NativeCallException _) {
                // reported as no_new_privs: false
            }
            abi = kernel.landlockAbi();
        }
        var confinement = Confinement.forAbi(abi, noNewPrivs);
        out.println("{\"os\":" + Json.quote(os.jsonName()) + ",\"landlock_abi\":" + abi + ",\"no_new_privs\":"
                + confinement.noNewPrivs() + ",\"confinement\":" + Json.quote(confinement.state()) + "}");
        out.flush();
        return 0;
    }

    private int launch(Command.Launch launch) throws UsageException, NativeCallException {
        var confinement = os == Os.LINUX ? new LinuxConfinement(kernel, root).apply(launch)
                : Confinement.forAbi(0, false);
        kernel.setProcessGroup();
        var self = ProcessHandle.current();
        var started = self.info().startInstant().map(Instant::toString).map(Json::quote).orElse("null");
        err.println("{\"pm_exec\":\"launched\",\"pid\":" + self.pid() + ",\"process_started_at\":" + started
                + ",\"confinement\":" + Json.quote(confinement.state()) + ",\"landlock_abi\":"
                + confinement.abiJson() + ",\"no_new_privs\":" + confinement.noNewPrivs() + "}");
        err.flush();
        int errno = kernel.execve(launch.argv());
        return error("execve", errno, "cannot execute " + launch.program(), EXIT_EXEC);
    }

    private int error(String stage, int errno, String message, int exitCode) {
        err.println("{\"pm_exec\":\"error\",\"stage\":" + Json.quote(stage) + ",\"errno\":" + errno
                + ",\"message\":" + Json.quote(message) + "}");
        err.flush();
        return exitCode;
    }
}
