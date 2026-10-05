/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.util.Map;


import de.cuioss.pm.mcp.server.test.TestRuntime;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@DisplayName("HelloTool over MCP Streamable HTTP")
class HelloToolTest {

    @TestHTTPResource
    URI testUri;

    @BeforeEach
    void setUp() {
        McpAssured.baseUri = testUri;
    }

    @Test
    @DisplayName("tools/list exposes the hello tool")
    void shouldListHelloTool() {
        var client = McpAssured.newStreamableClient().setBearerToken(TestRuntime.token()).build().connect();
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
    void shouldGreet() {
        var client = McpAssured.newStreamableClient().setBearerToken(TestRuntime.token()).build().connect();
        client.when()
                .toolsCall("hello", Map.of("name", "World"), response -> {
                    assertFalse(response.isError());
                    assertEquals("Hello, World!", response.content().getFirst().asText().text());
                })
                .thenAssertResults();
    }
}
