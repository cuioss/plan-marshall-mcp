/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.relay;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * What a command of {@code pm-mcp} takes from its process: the environment, {@code stdin}, the
 * working directory and the user's home directory. Tests pass their own.
 *
 * @param environment      the environment
 * @param stdin            the standard input
 * @param workingDirectory the working directory
 * @param userHome         the user's home directory
 */
record CliContext(Map<String, String> environment, InputStream stdin, Path workingDirectory, Path userHome) {

    /**
     * Copies the environment.
     *
     * @param environment      the environment
     * @param stdin            the standard input
     * @param workingDirectory the working directory
     * @param userHome         the user's home directory
     */
    CliContext {
        environment = Map.copyOf(environment);
    }

    /** @return the context of the running process */
    static CliContext system() {
        return new CliContext(System.getenv(), new FileInputStream(FileDescriptor.in),
                Path.of("").toAbsolutePath(), Path.of(System.getProperty("user.home")));
    }
}
