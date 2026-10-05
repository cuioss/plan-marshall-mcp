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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point of the native binary {@code pm-exec}; the protocol is described in the package
 * documentation. Only the runtime {@code pm-mcpd} starts it.
 *
 * @since 0.1
 */
public final class PmExec {

    private PmExec() {
    }

    /**
     * Probes the platform or launches a job; returns only when no {@code execve} happened.
     *
     * @param args the argument vector
     */
    public static void main(String[] args) {
        var out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        var err = new PrintStream(new FileOutputStream(FileDescriptor.err), false, StandardCharsets.UTF_8);
        System.exit(run(args, out, err));
    }

    /**
     * Runs one invocation on the real platform.
     *
     * @param args the argument vector
     * @param out  {@code stdout}
     * @param err  {@code stderr}
     * @return the exit code; a successful launch never returns
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        var os = Os.of(System.getProperty("os.name"));
        return new Launcher(os, new NativeKernel(os), Path.of("/"), out, err).run(List.of(args));
    }
}
