/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.web;

/**
 * The web access setting and the live state of its listener, the {@code web} object of the runtime status.
 *
 * @param enabled whether web access is enabled
 * @param lan     whether the listener serves all interfaces over TLS instead of loopback over plain HTTP
 * @param port    the listener port
 * @param open    whether the listener is open
 * @since 0.1
 */
public record WebState(boolean enabled, boolean lan, int port, boolean open) {

    /** The compiled default port. */
    public static final int DEFAULT_PORT = 7420;

    /** @return the state of a runtime with web access off */
    public static WebState disabled() {
        return new WebState(false, false, DEFAULT_PORT, false);
    }
}
