/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.conformance;

/**
 * How a verdict was measured (PM-TEST-6): with the rule under test alone, or with the content a session holds
 * anyway.
 *
 * @since 0.1
 */
public enum Attribution {
    /** The rule was measured on its own. */
    ISOLATED("isolated"),
    /** The rule is also stated in resident content and can only be measured with it. */
    REALISTIC_CONTEXT("realistic_context");

    private final String wireName;

    Attribution(String wireName) {
        this.wireName = wireName;
    }

    /**
     * @return the value in a scenario record, a verdict record and a report
     */
    public String wireName() {
        return wireName;
    }
}
