/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.credentials.dbus;

import java.io.Serial;

/**
 * A D-Bus failure: an {@code ERROR} reply of the peer (with its error name), a malformed message,
 * or a failed or timed-out transport.
 */
public class DbusException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Error name for failures detected locally (protocol, transport, timeout). */
    public static final String LOCAL = "local";

    /** The D-Bus error name, or {@link #LOCAL}. */
    private final String errorName;

    /**
     * @param errorName the D-Bus error name, or {@link #LOCAL}
     * @param message   the description
     */
    public DbusException(String errorName, String message) {
        super(message);
        this.errorName = errorName;
    }

    /**
     * @param message the description of a local failure
     * @param cause   the cause
     */
    public DbusException(String message, Throwable cause) {
        super(message, cause);
        this.errorName = LOCAL;
    }

    /** @return the D-Bus error name, or {@link #LOCAL} */
    public String errorName() {
        return errorName;
    }
}
