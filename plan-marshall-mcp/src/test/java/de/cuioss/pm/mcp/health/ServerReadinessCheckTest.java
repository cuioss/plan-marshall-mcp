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
package de.cuioss.pm.mcp.health;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;


import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@DisplayName("ServerReadinessCheck")
class ServerReadinessCheckTest {

    @TestHTTPResource(value = "/health/ready", management = true)
    URL readinessUrl;

    @Test
    @DisplayName("reports UP")
    void shouldReportUp() {
        var response = new ServerReadinessCheck().call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(ServerReadinessCheck.NAME, response.getName());
    }

    @Test
    @DisplayName("is exposed on the readiness endpoint")
    void shouldBeExposedOnReadinessEndpoint() {
        var status = given().when().get(readinessUrl)
                .then().statusCode(200)
                .extract().jsonPath().getString("checks.find { it.name == '" + ServerReadinessCheck.NAME + "' }.status");

        assertEquals("UP", status);
    }
}
