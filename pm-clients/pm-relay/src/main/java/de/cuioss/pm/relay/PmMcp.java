/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.relay;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import de.cuioss.pm.api.PmVersion;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The host-facing command line {@code pm-mcp}: the verbs a host configuration, a worker's composed
 * MCP configuration, or an entry command runs.
 */
@Command(name = "pm-mcp", mixinStandardHelpOptions = true, versionProvider = PmMcp.Version.class,
        description = "Host-facing command line of PM-MCP.", subcommands = ServeCommand.class)
public final class PmMcp {

    /** Supplies the release version to {@code --version}. */
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"pm-mcp " + PmVersion.current()};
        }
    }

    private PmMcp() {
    }

    /**
     * Runs the command line.
     *
     * @param args the arguments
     */
    public static void main(String[] args) {
        var out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.out),
                StandardCharsets.UTF_8), false);
        var err = new PrintWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.err),
                StandardCharsets.UTF_8), true);
        System.exit(execute(CliContext.system(), out, err, args));
    }

    /**
     * @param context the process context
     * @param out     standard output
     * @param err     standard error
     * @param args    the arguments
     * @return the exit code
     */
    static int execute(CliContext context, PrintWriter out, PrintWriter err, String... args) {
        var commandLine = new CommandLine(new PmMcp(), new Factory(context));
        commandLine.setOut(out);
        commandLine.setErr(err);
        var code = commandLine.execute(args);
        out.flush();
        err.flush();
        return code;
    }

    /** Creates the commands with the process context. */
    private record Factory(CliContext context) implements CommandLine.IFactory {
        @Override
        public <K> K create(Class<K> type) throws Exception {
            if (type == ServeCommand.class) {
                return type.cast(new ServeCommand(context));
            }
            if (type == PmMcp.class) {
                return type.cast(new PmMcp());
            }
            return CommandLine.defaultFactory().create(type);
        }
    }
}
