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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A parsed {@code pm-exec} argument vector (see the package documentation for the protocol).
 */
sealed interface Command {

    /** Option printing the platform probe. */
    String PROBE = "--probe";
    /** Option adding a path to the write set. */
    String WRITE = "--write";
    /** Option adding a path to the read set. */
    String READ = "--read";
    /** Option naming {@code <PM_MCP_BASE>}, excluded from the read set. */
    String DENY_READ = "--deny-read";
    /** End of the options. */
    String END_OF_OPTIONS = "--";

    /** {@code pm-exec --probe}. */
    record Probe() implements Command {
    }

    /**
     * A job launch.
     *
     * @param writePaths the write set
     * @param readPaths  the read paths beyond the enumerated read set
     * @param deniedBase {@code <PM_MCP_BASE>}, when given
     * @param argv       the program (absolute path) followed by its arguments
     */
    record Launch(List<Path> writePaths, List<Path> readPaths, Optional<Path> deniedBase, List<String> argv)
            implements Command {

        /**
         * Copies the lists.
         *
         * @param writePaths the write set
         * @param readPaths  the extra read paths
         * @param deniedBase {@code <PM_MCP_BASE>}
         * @param argv       program and arguments
         */
        public Launch {
            writePaths = List.copyOf(writePaths);
            readPaths = List.copyOf(readPaths);
            argv = List.copyOf(argv);
        }

        /** @return the absolute path of the program */
        String program() {
            return argv.getFirst();
        }
    }

    /**
     * Parses an argument vector.
     *
     * @param args the arguments without the binary name
     * @return the command
     * @throws UsageException for an argument vector outside the protocol
     */
    static Command parse(List<String> args) throws UsageException {
        if (args.size() == 1 && PROBE.equals(args.getFirst())) {
            return new Probe();
        }
        var writes = new ArrayList<Path>();
        var reads = new ArrayList<Path>();
        Path denied = null;
        int i = 0;
        while (i < args.size() && !END_OF_OPTIONS.equals(args.get(i))) {
            var option = args.get(i);
            if (i + 1 >= args.size()) {
                throw new UsageException("option " + option + " needs a value");
            }
            var value = absolute(option, args.get(i + 1));
            switch (option) {
                case WRITE -> writes.add(value);
                case READ -> reads.add(value);
                case DENY_READ -> {
                    if (denied != null) {
                        throw new UsageException(DENY_READ + " given twice");
                    }
                    denied = value;
                }
                default -> throw new UsageException("unknown option " + option);
            }
            i += 2;
        }
        if (i >= args.size() - 1) {
            throw new UsageException("missing '-- <program>'");
        }
        var argv = args.subList(i + 1, args.size());
        absolute("program", argv.getFirst());
        return new Launch(writes, reads, Optional.ofNullable(denied), argv);
    }

    private static Path absolute(String what, String value) throws UsageException {
        if (value.isEmpty() || value.charAt(0) != '/' || value.indexOf('\0') >= 0) {
            throw new UsageException(what + " needs an absolute path: '" + value + "'");
        }
        return Path.of(value).normalize();
    }
}
