/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.runtime;

import java.io.IOException;
import java.io.Serial;

/**
 * The runtime answered {@code 401} and no other credential is left to try.
 */
public class UnauthorizedException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message the diagnostic, never containing a token
     */
    public UnauthorizedException(String message) {
        super(message);
    }
}
