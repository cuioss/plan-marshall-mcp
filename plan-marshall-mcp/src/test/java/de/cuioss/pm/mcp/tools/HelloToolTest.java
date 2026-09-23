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
package de.cuioss.pm.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.util.Map;


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
        var client = McpAssured.newConnectedStreamableClient();
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
        var client = McpAssured.newConnectedStreamableClient();
        client.when()
                .toolsCall("hello", Map.of("name", "World"), response -> {
                    assertFalse(response.isError());
                    assertEquals("Hello, World!", response.content().getFirst().asText().text());
                })
                .thenAssertResults();
    }
}
