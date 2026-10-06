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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.pm.mcp.server.test.TestRuntime;
import de.cuioss.pm.mcp.server.test.UdsHttp;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(SpikeJobToolsTest.Jobs.class)
@DisplayName("Worker identity of the stub from minted job tokens")
class SpikeJobToolsTest {

    private static final String JOBS = "/api/v1/spike/jobs";
    private static final String TASK_ID = "task_id";

    public static class Jobs implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("pm.spike.scenario", "target/test-classes/spike/jobs.json",
                    "pm.spike.run-dir", "target/spike-jobs-run-" + System.nanoTime());
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

    private static UdsHttp.Response api(String method, String path, String body) throws IOException {
        var headers = new HashMap<>(TestRuntime.bearer(TestRuntime.token()));
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        return UdsHttp.request(TestRuntime.paths().socket(), method, path, headers, body);
    }

    private static JsonObject mint(String worker, int generation) throws IOException {
        var response = api("POST", JOBS, new JsonObject().put("worker", worker).put("generation", generation)
                .put("role", "worker").encode());
        assertEquals(201, response.status(), response.body());
        return new JsonObject(response.body());
    }

    private static McpStreamableTestClient worker(String token) {
        return McpAssured.newStreamableClient().setOpenSubsidiarySse(false)
                .setAdditionalHeaders(_ -> MultiMap.caseInsensitiveMultiMap().add("PM-MCP-Job-Token", token))
                .build().connect();
    }

    private static JsonObject call(McpStreamableTestClient client, String tool, Map<String, Object> arguments,
            boolean error) {
        var result = new JsonObject[1];
        client.when().toolsCall(tool, arguments, response -> {
            assertEquals(error, response.isError(), response.content().getFirst().asText().text());
            result[0] = new JsonObject(response.content().getFirst().asText().text());
        }).thenAssertResults();
        return result[0];
    }

    private List<JsonObject> events(String name) throws IOException {
        return Files.readAllLines(Path.of(runDir, SpikeEventLog.FILE_NAME)).stream().map(JsonObject::new)
                .filter(event -> name.equals(event.getString("event"))).toList();
    }

    @Test
    @DisplayName("takes worker and generation from the job, releases the lease at revocation and refuses the old generation")
    void shouldFenceRevokedGeneration() throws Exception {
        var first = mint("w1", 1);
        assertEquals("j-w1-g1", first.getString("job_id"));
        var old = worker(first.getString("token"));

        var offer = call(old, SpikeTools.PULL_WAIT, Map.of(), false);
        assertEquals("task", offer.getString("status"));
        assertEquals("t1", offer.getString(TASK_ID));
        call(old, SpikeTools.PULL_ACK, Map.of(TASK_ID, "t1"), false);
        var offered = events("offer").getFirst();
        assertEquals("w1", offered.getString("connection"));
        assertEquals(1, offered.getInteger("generation"));

        var revoked = api("DELETE", JOBS + "/j-w1-g1", null);
        assertEquals(200, revoked.status(), revoked.body());
        assertEquals(List.of("t1"), new JsonObject(revoked.body()).getJsonArray("released").getList());

        var late = call(old, SpikeTools.PULL_SUBMIT, Map.of(TASK_ID, "t1", "decision", "a"), true);
        assertEquals(SpikeEngine.STALE, late.getString("reason"));

        var successor = worker(mint("w1", 2).getString("token"));
        var again = call(successor, SpikeTools.PULL_WAIT, Map.of("worker", "spoofed", "generation", 9), false);
        assertEquals("t1", again.getString(TASK_ID));
        var redelivered = events("offer").getLast();
        assertEquals("w1", redelivered.getString("connection"));
        assertEquals(2, redelivered.getInteger("generation"));
        assertTrue(events("stale_refused").stream().anyMatch(event -> event.getInteger("generation") == 1));
    }

    @Test
    @DisplayName("refuses a malformed mint with 400 and an unknown revocation with 404")
    void shouldRefuseBadRequests() throws Exception {
        assertEquals(400, api("POST", JOBS, new JsonObject().put("worker", "w 1").put("generation", 1).encode())
                .status());
        assertEquals(400, api("POST", JOBS, new JsonObject().put("worker", "w1").put("generation", 0).encode())
                .status());
        assertEquals(404, api("DELETE", JOBS + "/j-none-g1", null).status());
    }

    @Test
    @DisplayName("refuses an unknown job token with 401")
    void shouldRefuseUnknownToken() throws Exception {
        var headers = new HashMap<>(Map.of("PM-MCP-Job-Token", "pmj_unknown", "Content-Type", "application/json",
                "Accept", "application/json, text/event-stream"));
        var response = UdsHttp.request(TestRuntime.paths().socket(), "POST", "/mcp", headers,
                TestRuntime.statelessMessage(1, "tools/list", new JsonObject()).encode());
        assertEquals(401, response.status());
    }
}
