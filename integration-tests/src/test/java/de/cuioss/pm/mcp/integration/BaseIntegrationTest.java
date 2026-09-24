/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: FSL-1.1-ALv2
 *
 * Licensed under the Functional Source License, Version 1.1, ALv2 Future License
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE.md file at the root of this
 * repository or at https://github.com/cuioss/plan-marshall-mcp/blob/main/LICENSE.md
 */
package de.cuioss.pm.mcp.integration;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for integration tests against the running plan-marshall-mcp container.
 * <p>
 * Ports are passed by failsafe as system properties and default to the values published
 * in {@code docker-compose.yml}.
 */
abstract class BaseIntegrationTest {

    private static final String DEFAULT_HTTP_PORT = "18080";
    private static final String DEFAULT_MANAGEMENT_PORT = "19000";

    @BeforeAll
    static void setUpBaseIntegrationTest() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = httpPort();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    static int httpPort() {
        return Integer.parseInt(System.getProperty("test.http.port", DEFAULT_HTTP_PORT));
    }

    static RequestSpecification givenManagement() {
        return RestAssured.given()
                .baseUri("http://localhost:" + System.getProperty("test.management.port", DEFAULT_MANAGEMENT_PORT))
                .basePath("/q");
    }
}
