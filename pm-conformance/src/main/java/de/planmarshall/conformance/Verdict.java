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
 * The verdict of a scenario over its population of samples (PM-TEST-6). It exists at the aggregated level alone:
 * the observation of one sample is boolean, and a single run is reported as a sample, never as a verdict.
 *
 * @since 0.1
 */
public enum Verdict {
    /** The held rate lies in the tolerance band of the scenario. */
    HELD("held"),
    /** The held rate lies below the tolerance band. */
    VIOLATED("violated"),
    /** The run cannot be scored against the band; it never counts as held. */
    INDETERMINATE("indeterminate");

    private final String wireName;

    Verdict(String wireName) {
        this.wireName = wireName;
    }

    /**
     * @return the value in a scenario record, a verdict record and a report
     */
    public String wireName() {
        return wireName;
    }
}
