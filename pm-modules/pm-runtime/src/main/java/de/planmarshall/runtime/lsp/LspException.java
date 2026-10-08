/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.lsp;

import java.io.Serial;


import lombok.Getter;

/**
 * A failure of a language-server interaction.
 *
 * @since 0.1
 */
public final class LspException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The kind of failure. */
    public enum Kind {
        /** The process could not be started or {@code initialize} failed. */
        START_FAILED,
        /** A request did not answer within its timeout. */
        TIMEOUT,
        /** The server answered a request with an error. */
        REQUEST_FAILED,
        /** The calling thread was interrupted. */
        INTERRUPTED
    }

    /** The kind of failure. */
    @Getter
    private final Kind kind;

    /**
     * @param kind    the kind
     * @param message the detail
     * @param cause   the cause, or {@code null}
     */
    public LspException(Kind kind, String message, Throwable cause) {
        super(kind + ": " + message, cause);
        this.kind = kind;
    }
}
