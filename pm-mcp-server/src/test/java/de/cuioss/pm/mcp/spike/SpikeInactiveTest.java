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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;


import de.cuioss.pm.mcp.server.test.TestRuntime;
import de.cuioss.pm.mcp.server.test.UdsHttp;
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

    @Test
    @DisplayName("answers 404 to the job endpoint of the harness drivers")
    void shouldHideJobEndpoint() throws Exception {
        var headers = new HashMap<>(TestRuntime.bearer(TestRuntime.token()));
        headers.put("Content-Type", "application/json");
        var socket = TestRuntime.paths().socket();

        assertEquals(404, UdsHttp.request(socket, "POST", "/api/v1/spike/jobs", headers,
                "{\"worker\":\"w1\",\"generation\":1}").status());
        assertEquals(404, UdsHttp.request(socket, "DELETE", "/api/v1/spike/jobs/j-w1-g1", headers, null).status());
    }
}
