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
 * Thrown when a value cannot be encoded conformantly by the PM-MCP subset encoder.
 *
 * @since 0.1
 */
public final class ToonEncodingException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why a value was refused. */
    public enum Reason {
        /** The official specification requires the list form (§ 9.2, § 9.4, § 10), outside the subset. */
        LIST_FORM,
        /** The official specification requires the keyed tabular form (§ 9.5), outside the subset. */
        KEYED_TABULAR_FORM,
        /** The official specification requires a nested field group (§ 9.3), outside the subset. */
        NESTED_FIELD_GROUP,
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
