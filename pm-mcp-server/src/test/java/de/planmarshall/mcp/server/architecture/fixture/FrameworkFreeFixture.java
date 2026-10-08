/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.architecture.fixture;

/** Logic without a framework type: trips the rule, since it would belong in a library module. */
public final class FrameworkFreeFixture {

    /**
     * @param value any value
     * @return its length
     */
    public int length(String value) {
        return value.length();
    }
}
