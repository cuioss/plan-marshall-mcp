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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.util.Map;


import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Talks to the containerised server through the Streamable HTTP transport with the McpAssured
 * client of quarkus-mcp-server.
 */
@DisplayName("MCP hello tool in the container")
class HelloToolIT extends BaseIntegrationTest {

    private McpStreamableTestClient client;

    @BeforeEach
    void connect() {
        client = McpAssured.newStreamableClient()
                .setBaseUri(URI.create("http://localhost:" + httpPort()))
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
                .toolsCall("hello", Map.of("name", "Container"), response -> {
                    assertFalse(response.isError());
                    assertEquals("Hello, Container!", response.content().getFirst().asText().text());
                })
                .thenAssertResults();
    }
}
