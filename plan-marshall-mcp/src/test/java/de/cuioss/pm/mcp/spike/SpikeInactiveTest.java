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
package de.cuioss.pm.mcp.spike;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;


import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@DisplayName("Pull-mechanism stub without a scenario")
class SpikeInactiveTest {

    @TestHTTPResource
    URI testUri;

    @Test
    @DisplayName("registers no stub tool when pm.spike.scenario is not set")
    void shouldStayInactive() {
        McpAssured.baseUri = testUri;
        var client = McpAssured.newConnectedStreamableClient();

        client.when()
                .toolsList(page -> {
                    assertNotNull(page.findByName("hello"));
                    assertTrue(page.tools().stream().noneMatch(tool -> tool.name().startsWith("pull_")),
                            "no stub tool may be listed");
                })
                .thenAssertResults();
    }
}
