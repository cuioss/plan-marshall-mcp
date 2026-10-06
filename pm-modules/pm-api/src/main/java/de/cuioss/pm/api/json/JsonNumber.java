/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.json;

import java.util.Objects;

/**
 * A JSON number kept as its source text, so a relayed message keeps every number exactly.
 *
 * @param text the number as written in the source
 */
public record JsonNumber(String text) {

    /**
     * @param text the number text
     */
    public JsonNumber {
        Objects.requireNonNull(text, "text");
    }

    /**
     * @param value an integral value
     * @return the number
     */
    public static JsonNumber of(long value) {
        return new JsonNumber(Long.toString(value));
    }

    /**
     * @return the value as {@code long}
     * @throws NumberFormatException if the number is not integral or out of range
     */
    public long longValue() {
        return Long.parseLong(text);
    }

    @Override
    public String toString() {
        return text;
    }
}
