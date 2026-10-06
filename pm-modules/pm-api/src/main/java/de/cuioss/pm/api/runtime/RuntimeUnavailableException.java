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
 * No runtime answers: none runs and none may be started, or the started one did not answer an
 * authenticated round trip within the runtime start timeout.
 */
public class RuntimeUnavailableException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message the diagnostic
     */
    public RuntimeUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message the diagnostic
     * @param cause   the cause
     */
    public RuntimeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
