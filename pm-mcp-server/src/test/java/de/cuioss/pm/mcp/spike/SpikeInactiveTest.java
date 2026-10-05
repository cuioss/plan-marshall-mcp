/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;


import de.cuioss.pm.mcp.server.test.TestRuntime;
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
        var client = McpAssured.newStreamableClient().setBearerToken(TestRuntime.token()).build().connect();

        client.when()
                .toolsList(page -> {
                    assertNotNull(page.findByName("hello"));
                    assertTrue(page.tools().stream().noneMatch(tool -> tool.name().startsWith("pull_")),
                            "no stub tool may be listed");
                })
                .thenAssertResults();
    }
}
