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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;


import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(SpikeToolsTest.ActiveStub.class)
@DisplayName("Pull-mechanism stub over MCP Streamable HTTP")
class SpikeToolsTest {


    public static class ActiveStub implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            // A directory of its own per start: the event log is append-only.
            return Map.of("pm.spike.scenario", "target/test-classes/spike/scenario.json",
                    "pm.spike.run-dir", "target/spike-test-run-" + System.nanoTime());
        }
    }

    @TestHTTPResource
    URI testUri;

    @ConfigProperty(name = "pm.spike.run-dir")
    String runDir;

    @BeforeEach
    void setUp() {
        McpAssured.baseUri = testUri;
    }

    private static JsonObject body(ToolResponse response) {
        return new JsonObject(response.content().getFirst().asText().text());
    }

    private List<JsonObject> events(String name) throws IOException {
        return Files.readAllLines(Path.of(runDir, SpikeEventLog.FILE_NAME)).stream().map(JsonObject::new)
                .filter(event -> name.equals(event.getString("event"))).toList();
    }

    @Test
    @DisplayName("runs the scenario: wait with progress, task, submit, widened tool set, done")
    void shouldRunScenario() throws Exception {
        var client = McpAssured.newConnectedStreamableClient();

        // One exchange per step: the test client sends everything queued before thenAssertResults at once.
        client.when()
                .toolsList(page -> {
                    assertNotNull(page.findByName(SpikeTools.PULL_WAIT));
                    assertNotNull(page.findByName(SpikeTools.PULL_SUBMIT));
                    assertNotNull(page.findByName(SpikeTools.PULL_INFO));
                    assertTrue(page.tools().stream()
                            .noneMatch(tool -> SpikeTools.PULL_ESCALATE.equals(tool.name())));
                })
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_WAIT)
                .withMetadata(Map.of("progressToken", "p1"))
                .withAssert(response -> assertEquals(SpikeEngine.WAIT_AGAIN,
                        body(response).getString(SpikeEngine.STATUS)))
                .send()
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_INFO, response -> assertFalse(response.isError()))
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_WAIT, response -> {
                    assertEquals(SpikeEngine.TASK, body(response).getString(SpikeEngine.STATUS));
                    assertEquals("t1", body(response).getString("task_id"));
                })
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_SUBMIT, Map.of("task_id", "other", "decision", "a"), response -> {
                    assertTrue(response.isError());
                    assertEquals("unknown_task", body(response).getString("reason"));
                })
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_SUBMIT, Map.of("task_id", "t1", "decision", 7), response -> {
                    assertTrue(response.isError());
                    assertEquals("missing_argument", body(response).getString("reason"));
                })
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_SUBMIT, Map.of("task_id", "t1", "decision", "a", "rationale", "r"),
                        response -> assertTrue(body(response).getBoolean("accepted")))
                .thenAssertResults();
        client.when()
                .toolsList(page -> assertNotNull(page.findByName(SpikeTools.PULL_ESCALATE)))
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_ESCALATE, response -> assertFalse(response.isError()))
                .thenAssertResults();
        client.when()
                .toolsCall(SpikeTools.PULL_WAIT, response -> assertEquals(SpikeEngine.DONE,
                        body(response).getString(SpikeEngine.STATUS)))
                .thenAssertResults();

        client.when()
                .toolsCall(SpikeTools.PULL_WAIT, Map.of(SpikeTools.WORKER, "w1"), response -> assertEquals(
                        SpikeEngine.DONE, body(response).getString(SpikeEngine.STATUS)))
                .thenAssertResults();

        assertEquals(1, events("run_start").size());
        assertTrue(events("rx").stream().anyMatch(event -> "initialize".equals(event.getString("method"))
                && event.getJsonObject("client_info") != null), "initialize with clientInfo must be recorded");
        assertFalse(events("progress").isEmpty(), "progress frames must be sent for a call with a token");
        var waited = events("wait_end").getFirst();
        assertEquals(SpikeEngine.WAIT_AGAIN, waited.getString("outcome"));
        assertTrue(waited.getLong("held_ms") >= 400, "the wait must block for the scenario's duration");
        assertEquals(1, events("info_called").size());
        var starts = events("wait_start");
        assertEquals("w1", starts.getLast().getString("connection"), "the worker argument must identify the caller");
        assertNotEquals("w1", starts.getFirst().getString("connection"));
        assertEquals(starts.getFirst().getString("mcp_connection"), starts.getLast().getString("mcp_connection"));
        assertEquals(1, events("tool_added").size());
        assertEquals(1, events("escalate_called").size());
        assertEquals("t1", events("submit").getFirst().getString("task_id"));
    }
}
