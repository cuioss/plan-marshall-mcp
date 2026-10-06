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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;


import de.cuioss.pm.mcp.server.test.TestRuntime;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(SpikeImplicitAckToolsTest.Implicit.class)
@DisplayName("Implicit acknowledgement of the stub over MCP Streamable HTTP")
class SpikeImplicitAckToolsTest {


    public static class Implicit implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("pm.spike.scenario", "target/test-classes/spike/protocol-implicit.json",
                    "pm.spike.run-dir", "target/spike-implicit-run-" + System.nanoTime());
        }
    }

    @TestHTTPResource
    URI testUri;

    @ConfigProperty(name = "pm.spike.run-dir")
    String runDir;

    private static JsonObject body(ToolResponse response) {
        return new JsonObject(response.content().getFirst().asText().text());
    }

    @Test
    @DisplayName("offers the question, returns the facts through pull_task and counts that call as the acknowledgement")
    void shouldAcknowledgeByTaskCall() throws Exception {
        McpAssured.baseUri = testUri;
        var client = McpAssured.newStreamableClient().setBearerToken(TestRuntime.token()).build().connect();

        client.when()
                .toolsList(page -> {
                    assertNotNull(page.findByName(SpikeTools.PULL_TASK));
                    // pm_* names are the core tools of the server (with their own descriptions), none of the stub
                    assertTrue(page.tools().stream().noneMatch(tool -> SpikeTools.PULL_ACK.equals(tool.name())
                            || tool.name().startsWith("pm_") && !CoreToolsAccess.isCore(tool.name(),
                            tool.description())
                            || tool.name().startsWith("spike_")));
                })
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_WAIT, response -> {
                    assertEquals("t1", body(response).getString("task_id"));
                    assertFalse(body(response).getJsonObject("task").containsKey("facts"));
                })
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_TASK, Map.of("task_id", "t9"), response -> {
                    assertTrue(response.isError());
                    assertEquals("unknown_task", body(response).getString("reason"));
                })
                .toolsCall(SpikeTools.PULL_TASK, Map.of("task_id", "t1"), response -> assertEquals("the facts",
                        body(response).getJsonObject("task").getString("facts")))
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_SUBMIT, Map.of("task_id", "t1", "decision", "a"),
                        response -> assertTrue(body(response).getBoolean("accepted")))
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_WAIT, response -> assertEquals(SpikeEngine.END,
                        body(response).getString(SpikeEngine.STATUS)))
                .thenAssertResults();

        var events = Files.readAllLines(Path.of(runDir, SpikeEventLog.FILE_NAME)).stream().map(JsonObject::new)
                .toList();
        var ack = events.stream().filter(event -> "ack".equals(event.getString("event"))).toList();
        assertEquals(1, ack.size());
        assertEquals("implicit", ack.getFirst().getString("variant"));
        assertTrue(events.stream().anyMatch(event -> "submit".equals(event.getString("event"))
                && event.getBoolean("acked")));
    }
}
