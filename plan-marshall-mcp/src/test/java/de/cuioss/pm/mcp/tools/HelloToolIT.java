/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.util.Map;


import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Talks to the packaged application through the Streamable HTTP transport with the McpAssured
 * client of quarkus-mcp-server.
 */
@QuarkusIntegrationTest
@DisplayName("MCP hello tool of the packaged application")
class HelloToolIT {

    @TestHTTPResource
    URI testUri;

    private McpStreamableTestClient client;

    @BeforeEach
    void connect() {
        client = McpAssured.newStreamableClient()
                .setBaseUri(testUri)
                .build()
                .connect();
    }

    @AfterEach
    void disconnect() {
        // null if connect() failed: don't mask that failure with an NPE
        if (client != null) {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("initialize reports the server name")
    void shouldReportServerName() {
        assertEquals("plan-marshall-mcp", client.initResult().serverName());
    }

    @Test
    @DisplayName("tools/list exposes the hello tool")
    void shouldListHelloTool() {
        client.when()
                .toolsList(page -> {
                    var tool = page.findByName("hello");
                    assertNotNull(tool, "hello tool must be listed");
                    assertEquals("Returns a greeting for the given name.", tool.description());
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("tools/call hello returns the greeting")
    void shouldGreetViaMcp() {
        client.when()
                .toolsCall("hello", Map.of("name", "Integration"), response -> {
                    assertFalse(response.isError());
                    assertEquals("Hello, Integration!", response.content().getFirst().asText().text());
                })
                .thenAssertResults();
    }
}
