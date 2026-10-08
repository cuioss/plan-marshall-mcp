/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The installation directory {@code <PM_MCP_HOME>}: the environment variable {@code PM_MCP_HOME}
 * when set, otherwise the parent of the directory containing the running executable with every
 * symbolic link resolved, so a binary reached through a {@code PATH} symlink finds its own
 * installation. Siblings are started by their absolute path in {@code bin/}, never through
 * {@code PATH}.
 *
 * @param directory the installation directory, absolute and normalized
 */
public record PmMcpHome(Path directory) {

    /** Environment variable naming the installation directory. */
    public static final String ENV_HOME = "PM_MCP_HOME";

    /** File name of the runtime binary. */
    public static final String DAEMON_BINARY = "pm-mcpd";

    /**
     * @param directory the installation directory
     */
    public PmMcpHome {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    /**
     * Resolves the installation directory.
     *
     * @param environment the process environment
     * @param executable  the running executable, if known
     * @return the installation directory
     * @throws IOException if neither the variable nor the executable names one
     */
    public static PmMcpHome resolve(Map<String, String> environment, Optional<Path> executable) throws IOException {
        var configured = environment.get(ENV_HOME);
        if (configured != null && !configured.isBlank()) {
            return new PmMcpHome(Path.of(configured));
        }
        var binary = executable.orElseThrow(() -> new RuntimeUnavailableException(
                "Cannot resolve PM_MCP_HOME: the running executable is unknown and PM_MCP_HOME is not set"));
        var bin = binary.toRealPath().getParent();
        if (bin == null || bin.getParent() == null) {
            throw new RuntimeUnavailableException("Cannot resolve PM_MCP_HOME from the executable " + binary);
        }
        return new PmMcpHome(bin.getParent());
    }

    /**
     * Resolves the installation of the running process.
     *
     * @param environment the process environment
     * @return the installation directory
     * @throws IOException if it cannot be resolved
     */
    public static PmMcpHome current(Map<String, String> environment) throws IOException {
        return resolve(environment, ProcessHandle.current().info().command().map(Path::of));
    }

    /**
     * @param name a binary name
     * @return {@code <PM_MCP_HOME>/bin/<name>}
     */
    public Path binary(String name) {
        return directory.resolve("bin").resolve(name);
    }

    /** @return {@code <PM_MCP_HOME>/bin/pm-mcpd} */
    public Path daemon() {
        return binary(DAEMON_BINARY);
    }
}
