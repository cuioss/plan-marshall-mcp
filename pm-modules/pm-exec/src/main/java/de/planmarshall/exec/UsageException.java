/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.exec;

import java.io.Serial;

/**
 * An argument vector outside the {@code pm-exec} protocol (exit code {@code 64}).
 */
final class UsageException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what is wrong
     */
    UsageException(String message) {
        super(message);
    }
}
