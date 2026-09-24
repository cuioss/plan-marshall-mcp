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
        client.disconnect();
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
