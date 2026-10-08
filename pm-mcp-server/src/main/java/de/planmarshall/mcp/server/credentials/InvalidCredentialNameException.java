/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.credentials;

import java.io.Serial;

/**
 * A credential key or project name outside {@code [a-zA-Z0-9._-]} (PM-CRED-3).
 */
public class InvalidCredentialNameException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param name the refused name
     */
    public InvalidCredentialNameException(String name) {
        super("invalid credential name: '" + name + "'");
    }
}
