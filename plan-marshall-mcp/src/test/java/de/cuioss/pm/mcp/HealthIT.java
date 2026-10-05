/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;


import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs against the packaged application — the built artifact that Quarkus starts for
 * {@link QuarkusIntegrationTest} — and checks its health endpoints on the management interface.
 */
@QuarkusIntegrationTest
@DisplayName("Health endpoints of the packaged application")
class HealthIT {

    @TestHTTPResource(management = true)
    URL management;

    @ParameterizedTest
    @ValueSource(strings = {"/health", "/health/live", "/health/ready"})
    @DisplayName("report UP")
    void shouldReportUp(String path) {
        var status = given().baseUri(management.toString()).when().get(path)
                .then().statusCode(200).contentType("application/json")
                .extract().jsonPath().getString("status");

        assertEquals("UP", status);
    }
}
