/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.web;

import java.io.Serial;

/**
 * The web listener cannot open: its port is occupied, or web access is enabled with the other exposure
 * (API error code {@code web_listener_conflict}).
 *
 * @since 0.1
 */
public class WebListenerConflictException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The API error code. */
    public static final String CODE = "web_listener_conflict";

    /**
     * @param message the detail, shown to the operator only
     * @param cause   the bind failure, if any
     */
    public WebListenerConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
