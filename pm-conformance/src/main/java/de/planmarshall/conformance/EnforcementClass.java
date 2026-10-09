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
 * The class of the rule a scenario tests (PM-TEST-6). The results of a report are grouped by it.
 *
 * @since 0.1
 */
public enum EnforcementClass {
    /** The engine enforces the rule; a scenario measures the attempts the engine rejected. */
    ENGINE("engine"),
    /** A check of the server verifies the rule after the fact. */
    CHECK("check"),
    /** The rule is stated to the model and nothing enforces it. */
    GUIDANCE("guidance");

    private final String wireName;

    EnforcementClass(String wireName) {
        this.wireName = wireName;
    }

    /**
     * @return the value in a scenario record, a verdict record and a report
     */
    public String wireName() {
        return wireName;
    }
}
