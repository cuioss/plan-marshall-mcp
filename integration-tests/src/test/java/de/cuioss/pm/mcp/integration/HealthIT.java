/*
 * Copyright © 2026-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * SPDX-License-Identifier: FSL-1.1-ALv2
 *
 * Licensed under the Functional Source License, Version 1.1, ALv2 Future License
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE.md file at the root of this
 * repository or at https://github.com/cuioss/plan-marshall-mcp/blob/main/LICENSE.md
 */
package de.cuioss.pm.mcp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Health endpoints of the container")
class HealthIT extends BaseIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/health", "/health/live", "/health/ready"})
    @DisplayName("report UP")
    void shouldReportUp(String path) {
        var status = givenManagement().when().get(path)
                .then().statusCode(200).contentType("application/json")
                .extract().jsonPath().getString("status");

        assertEquals("UP", status);
    }
}
