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

import io.vertx.core.json.JsonObject;

/** References a Vert.x type and exchanges {@link ExchangedRecordFixture}: allowed in the assembly. */
public final class FrameworkBoundFixture {

    /**
     * @param body a request body
     * @return the record the body carries
     */
    public ExchangedRecordFixture read(JsonObject body) {
        return new ExchangedRecordFixture(body.getString("value"));
    }
}
