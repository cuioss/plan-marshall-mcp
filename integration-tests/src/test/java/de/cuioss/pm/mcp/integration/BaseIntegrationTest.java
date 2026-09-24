/*
 * Copyright © 2026-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
