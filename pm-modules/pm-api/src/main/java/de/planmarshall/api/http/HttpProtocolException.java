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

/**
 * The runtime answered with bytes that are no valid HTTP/1.1 response.
 */
public class HttpProtocolException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what was malformed
     */
    public HttpProtocolException(String message) {
        super(message);
    }
}
