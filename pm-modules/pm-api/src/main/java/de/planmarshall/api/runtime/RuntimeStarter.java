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

/**
 * Makes sure a runtime answers, starting one if needed.
 */
@FunctionalInterface
public interface RuntimeStarter {

    /**
     * Returns once an authenticated round trip with a runtime has succeeded.
     *
     * @throws RuntimeUnavailableException  if no runtime answered within the runtime start timeout
     * @throws InsecureRuntimeFileException if the runtime-token check refuses a file
     * @throws IOException                  if the start procedure fails
     */
    void ensureRunning() throws IOException;
}
