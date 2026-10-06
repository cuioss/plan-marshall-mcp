/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.core.toon;

import java.io.Serial;
import lombok.Getter;

/**
 * Thrown when a value cannot be represented in TOON.
 *
 * @since 0.1
 */
public final class ToonEncodingException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why a value was refused. */
    public enum Reason {
        /** A string holds an unpaired surrogate, which TOON cannot represent (§ 3). */
        UNPAIRED_SURROGATE,
        /** An object holds two fields with the same key. */
        DUPLICATE_KEY
    }

    /** The reason of the refusal. */
    @Getter
    private final Reason reason;

    /**
     * @param reason  the reason
     * @param message the detail
     */
    public ToonEncodingException(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }
}
