/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.http;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;

/**
 * No process accepted a connection on the runtime's socket: the socket file is absent or stale.
 * Nothing of the request was sent, so repeating it is safe.
 */
public class RuntimeUnreachableException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param socket the socket path
     * @param cause  the connect failure
     */
    public RuntimeUnreachableException(Path socket, IOException cause) {
        super("No runtime accepts connections on " + socket, cause);
    }
}
