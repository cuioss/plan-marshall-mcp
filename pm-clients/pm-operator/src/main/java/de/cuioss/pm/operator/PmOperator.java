/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.operator;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import de.cuioss.pm.api.PmVersion;
import de.cuioss.pm.operator.spike.SpikeCommand;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The operator command line {@code pm-operator}: the verbs a human runs at a terminal.
 */
@Command(name = "pm-operator", mixinStandardHelpOptions = true, versionProvider = PmOperator.Version.class,
        description = "Operator command line of PM-MCP.",
        subcommands = {StatusCommand.class, WebCommand.class, RuntimeCommand.class, SpikeCommand.class})
public final class PmOperator {

    /** Exit code of a failed verb. */
    static final int EXIT_FAILURE = 1;

    /** Supplies the release version to {@code --version}. */
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"pm-operator " + PmVersion.current()};
        }
    }

    private PmOperator() {
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
        System.exit(execute(OperatorContext.system(), out, err, args));
    }

    /**
     * @param context the process context
     * @param out     standard output
     * @param err     standard error
     * @param args    the arguments
     * @return the exit code
     */
    static int execute(OperatorContext context, PrintWriter out, PrintWriter err, String... args) {
        var commandLine = new CommandLine(new PmOperator(), new Factory(context));
        commandLine.setOut(out);
        commandLine.setErr(err);
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            err.println(command.getCommandName() + ": " + exception.getMessage());
            err.flush();
            return EXIT_FAILURE;
        });
        var code = commandLine.execute(args);
        out.flush();
        err.flush();
        return code;
    }

    /** Creates the commands with the process context. */
    private record Factory(OperatorContext context) implements CommandLine.IFactory {
        @Override
        public <K> K create(Class<K> type) throws Exception {
            SpikeCommand.ClientSource clients = () -> context.runtime(true);
            Object command;
            if (type == PmOperator.class) {
                command = new PmOperator();
            } else if (type == StatusCommand.class) {
                command = new StatusCommand(context);
            } else if (type == WebCommand.class) {
                command = new WebCommand(context);
            } else if (type == RuntimeCommand.class) {
                command = new RuntimeCommand(context);
            } else if (type == SpikeCommand.class) {
                command = new SpikeCommand(clients);
            } else if (type == SpikeCommand.Keyring.class) {
                command = new SpikeCommand.Keyring(clients);
            } else {
                return CommandLine.defaultFactory().create(type);
            }
            return type.cast(command);
        }
    }
}
