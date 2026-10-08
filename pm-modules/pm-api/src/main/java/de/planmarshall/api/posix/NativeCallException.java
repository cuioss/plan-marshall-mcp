/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.posix;

import java.io.IOException;
import java.io.Serial;

/**
 * A C library function could not be called or reported a failure.
 */
public class NativeCallException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message the function and the failure
     */
    public NativeCallException(String message) {
        super(message);
    }

    /**
     * @param message the function and the failure
     * @param cause   the cause
     */
    public NativeCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
