/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.operator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import de.planmarshall.api.MachinePaths;
import de.planmarshall.api.PmVersion;
import de.planmarshall.api.runtime.RuntimeAccess;
import de.planmarshall.api.runtime.RuntimeClient;

/**
 * What an operator command takes from its process: the environment and the user's home directory,
 * and the runtime clients built from them. Tests pass their own.
 *
 * @param environment the environment
 * @param userHome    the user's home directory
 */
public record OperatorContext(Map<String, String> environment, Path userHome) {

    /**
     * Copies the environment.
     *
     * @param environment the environment
     * @param userHome    the user's home directory
     */
    public OperatorContext {
        environment = Map.copyOf(environment);
    }

    /** @return the context of the running process */
    static OperatorContext system() {
        return new OperatorContext(System.getenv(), Path.of(System.getProperty("user.home")));
    }

    /** @return the machine paths */
    MachinePaths paths() {
        return MachinePaths.resolve(environment, userHome, MachinePaths.Os.current());
    }

    /**
     * @param startOnDemand {@code true} to start the runtime when none answers
     * @return the runtime client of this command
     * @throws IOException if the client cannot be set up
     */
    RuntimeClient runtime(boolean startOnDemand) throws IOException {
        return RuntimeAccess.local(environment, userHome, Map.of("User-Agent", "pm-operator/" + PmVersion.current()),
                startOnDemand);
    }
}
