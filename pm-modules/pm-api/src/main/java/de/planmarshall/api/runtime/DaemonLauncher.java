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
import java.util.OptionalInt;

/**
 * Starts the runtime process detached from the caller.
 */
@FunctionalInterface
public interface DaemonLauncher {

    /**
     * A started runtime process.
     */
    interface Launched {

        /** @return the process id */
        int pid();

        /** @return the exit code once the process has ended, else empty */
        OptionalInt exitCode();
    }

    /**
     * @param executable the absolute path of {@code pm-mcpd}
     * @param log        the file {@code stdout} and {@code stderr} are appended to
     * @return the started process
     * @throws IOException if the process cannot be started
     */
    Launched launch(Path executable, Path log) throws IOException;
}
