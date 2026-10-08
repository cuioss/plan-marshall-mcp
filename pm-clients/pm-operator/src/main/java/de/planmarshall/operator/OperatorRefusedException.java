/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.operator;

import java.io.IOException;
import java.io.Serial;

import de.planmarshall.api.http.HttpResponse;

/**
 * The runtime answered an operator request with an unexpected status.
 */
public class OperatorRefusedException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param verb     the verb
     * @param response the response
     */
    public OperatorRefusedException(String verb, HttpResponse response) {
        super(verb + " refused by the runtime (HTTP " + response.status() + ")"
                + (response.body().length == 0 ? "" : ": " + response.bodyText().strip()));
    }
}
