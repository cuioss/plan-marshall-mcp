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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import io.quarkiverse.mcp.server.ClientCapability;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
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
@TestProfile(SpikeProtocolToolsTest.Supervised.class)
@DisplayName("Supervised protocol of the stub over MCP Streamable HTTP")
class SpikeProtocolToolsTest {

    private static final String TRIAGE = "skill://pm/role/triage/SKILL.md";
    private static final String EXAMPLES = "skill://pm/role/triage/examples.md";
    private static final String TASK_ID = "task_id";
    private static final Map<String, Object> WORKER = Map.of("worker", "w1", "generation", 1, "role", "worker");
    private static final Map<String, Object> SECOND = Map.of("worker", "w2", "generation", 1, "role", "worker");
    private static final Map<String, Object> CONSULTANT = Map.of("worker", "c1", "generation", 1, "role",
            SpikeEngine.CONSULTANT);


    public static class Supervised implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("pm.spike.scenario", "target/test-classes/spike/protocol-explicit.json",
                    "pm.spike.run-dir", "target/spike-protocol-run-" + System.nanoTime());
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

    private static Map<String, Object> with(Map<String, Object> caller, Object... pairs) {
        var arguments = new HashMap<>(caller);
        for (var i = 0; i < pairs.length; i += 2) {
            arguments.put((String) pairs[i], pairs[i + 1]);
        }
        return arguments;
    }

    private List<JsonObject> events(String name) throws IOException {
        return Files.readAllLines(Path.of(runDir, SpikeEventLog.FILE_NAME)).stream().map(JsonObject::new)
                .filter(event -> name.equals(event.getString("event"))).toList();
    }

    private static JsonObject call(McpStreamableTestClient client, String tool, Map<String, Object> arguments,
            boolean error) {
        var result = new JsonObject[1];
        client.when().toolsCall(tool, arguments, response -> {
            assertEquals(error, response.isError(), response.content().getFirst().asText().text());
            result[0] = body(response);
        }).thenAssertResults();
        return result[0];
    }

    @Test
    @DisplayName("runs offer, acknowledgement, consultation, fencing and skill delivery through the tools")
    void shouldRunProtocol() throws Exception {
        var client = McpAssured.newConnectedStreamableClient();
        var skillHost = McpAssured.newStreamableClient()
                .setClientCapabilities(new ClientCapability("extensions",
                        Map.of(SpikeTools.SKILLS_EXTENSION, Map.of())))
                .build().connect();

        client.when().toolsList(page -> {
            for (var name : List.of(SpikeTools.PULL_ACK, SpikeTools.PULL_CONSULT, SpikeTools.PM_SKILLS,
                    SpikeTools.PM_SKILL, SpikeTools.PM_SKILL_FILE, SpikeTools.SPIKE_STATE, SpikeTools.SPIKE_FENCE,
                    SpikeTools.SPIKE_COMPACTED, SpikeTools.SPIKE_SKILL_UPDATE)) {
                assertNotNull(page.findByName(name), name);
            }
            assertTrue(page.tools().stream().noneMatch(tool -> SpikeTools.PULL_TASK.equals(tool.name())));
        }).thenAssertResults();

        // offer with the skill in-band, acknowledgement
        var offer = call(client, SpikeTools.PULL_WAIT, WORKER, false);
        assertEquals(SpikeEngine.TASK, offer.getString(SpikeEngine.STATUS));
        assertEquals("t1", offer.getString(TASK_ID));
        assertTrue(offer.getJsonArray("skills").getJsonObject(0).getString("content").contains("typed fix"));
        assertEquals(SpikeTools.PULL_ACK, offer.getJsonObject("links").getJsonObject("ack").getString("tool"));
        assertTrue(call(client, SpikeTools.PULL_ACK, with(WORKER, TASK_ID, "t1"), false).getBoolean("accepted"));
        assertEquals(SpikeEngine.NOT_BOUND,
                call(client, SpikeTools.PULL_ACK, with(SECOND, TASK_ID, "t1"), true).getString("reason"));

        // consultation through the server
        assertEquals("c-t1", call(client, SpikeTools.PULL_CONSULT,
                with(WORKER, TASK_ID, "t1", "question", "a or b?"), false)
                .getString(SpikeEngine.CONSULTATION_ID));
        var question = call(client, SpikeTools.PULL_WAIT, CONSULTANT, false);
        assertEquals("c-t1", question.getString(TASK_ID));
        assertTrue(call(client, SpikeTools.PULL_SUBMIT, with(CONSULTANT, TASK_ID, "c-t1", "decision", "b"), false)
                .getBoolean("accepted"));
        var answer = call(client, SpikeTools.PULL_WAIT, WORKER, false);
        assertEquals(SpikeEngine.ANSWER, answer.getString(SpikeEngine.STATUS));
        assertEquals("b", answer.getString("answer"));
        assertTrue(call(client, SpikeTools.PULL_SUBMIT, with(WORKER, TASK_ID, "t1", "decision", "b",
                SpikeEngine.CONSULTATION_ID, "c-t1"), false).getBoolean("accepted"));

        // a host that declares the skills extension gets the skill by URI only
        var byUri = call(skillHost, SpikeTools.PULL_WAIT, SECOND, false);
        assertEquals("t2", byUri.getString(TASK_ID));
        assertEquals(TRIAGE, byUri.getJsonArray("skills").getJsonObject(0).getString("uri"));
        assertFalse(byUri.getJsonArray("skills").getJsonObject(0).containsKey("content"));

        // driver: state, fence, compaction, skill update
        var state = call(client, SpikeTools.SPIKE_STATE, Map.of(), false);
        assertEquals("w2", state.getJsonArray("leases").getJsonObject(0).getString("worker"));
        assertEquals(2, state.getInteger("submitted"));
        var fence = call(client, SpikeTools.SPIKE_FENCE, Map.of("worker", "w2", "generation", 1), false);
        assertEquals(List.of("t2"), fence.getJsonArray("released").getList());
        var stale = call(skillHost, SpikeTools.PULL_WAIT, SECOND, false);
        assertEquals(SpikeEngine.END, stale.getString(SpikeEngine.STATUS));
        assertEquals(SpikeEngine.STALE, call(skillHost, SpikeTools.PULL_SUBMIT,
                with(SECOND, TASK_ID, "t2", "decision", "a"), true).getString("reason"));
        assertEquals("w1", call(client, SpikeTools.SPIKE_COMPACTED, Map.of("worker", "w1"), false)
                .getString("worker"));
        var again = call(client, SpikeTools.PULL_WAIT, WORKER, false);
        assertEquals("t2", again.getString(TASK_ID));
        assertTrue(again.getJsonArray("skills").getJsonObject(0).containsKey("content"),
                "the skill must be delivered again after a compaction");
        var updated = call(client, SpikeTools.SPIKE_SKILL_UPDATE, Map.of("uri", TRIAGE, "content", "changed"), false);
        assertNotEquals(again.getJsonArray("skills").getJsonObject(0).getString("digest"),
                updated.getString("digest"));
        assertEquals("missing_argument", call(client, SpikeTools.PULL_SUBMIT,
                with(WORKER, TASK_ID, "t2", "decision", 7), true).getString("reason"));

        // a wait without due work is held for the bounded wait
        var waiting = call(client, SpikeTools.PULL_WAIT, CONSULTANT, false);
        assertEquals(SpikeEngine.WAIT_AGAIN, waiting.getString(SpikeEngine.STATUS));

        assertEquals(List.of("inband", "uri", "inband"),
                events("skill_delivered").stream().map(event -> event.getString("form")).toList());
        assertEquals(1, events("ack").size());
        assertEquals(1, events("consult_open").size());
        assertEquals(1, events("consult_answer").size());
        assertEquals("ok", events("submit").getFirst().getString("consultation_ref"));
        assertEquals(1, events("fenced").size());
        assertEquals(2, events("stale_refused").size());
        assertEquals(1, events("compacted").size());
        assertEquals(1, events("skill_updated").stream().filter(event -> TRIAGE.equals(event.getString("uri"))).count());
        assertTrue(events("rx").stream().anyMatch(event -> event.getBoolean("skills_extension", false)));
        var held = events("wait_end").getLast();
        assertEquals(SpikeEngine.WAIT_AGAIN, held.getString("outcome"));
        assertTrue(held.getLong("held_ms") >= 400, "the wait must be held for the scenario's duration");
        assertEquals("held", events("wait_start").getLast().getString(SpikeEngine.STATUS));
        assertNull(events("wait_end").getFirst().getLong("held_ms"));
    }

    @Test
    @DisplayName("serves the catalogue through the skill tools and as resources")
    void shouldServeSkills() throws Exception {
        var client = McpAssured.newConnectedStreamableClient();
        // an own URI: the other test replaces the triage skill
        call(client, SpikeTools.SPIKE_SKILL_UPDATE, Map.of("uri", "skill://pm/role/extra/SKILL.md", "content",
                "---\nname: extra\n---\nbody"), false);

        var manifests = call(client, SpikeTools.PM_SKILLS, Map.of(), false).getJsonArray("skills");
        client.when()
                .toolsCall(SpikeTools.PM_SKILL, Map.of("uri", "skill://pm/core/SKILL.md", "worker", "w9"),
                        response -> assertTrue(response.content().getFirst().asText().text()
                                .contains("Follow the offered links.")))
                .toolsCall(SpikeTools.PM_SKILL_FILE, Map.of("uri", EXAMPLES),
                        response -> assertTrue(response.content().getFirst().asText().text().startsWith("Example")))
                .toolsCall(SpikeTools.PM_SKILL, Map.of("uri", "skill://pm/none/SKILL.md"), response -> {
                    assertTrue(response.isError());
                    assertEquals("unknown_skill", body(response).getString("reason"));
                })
                .thenAssertResults();
        client.when()
                .resourcesList(page -> {
                    assertNotNull(page.findByUri("skill://pm/core/SKILL.md"));
                    assertNotNull(page.findByUri(TRIAGE));
                })
                .resourcesTemplatesList(page -> assertEquals(1, page.size()))
                .resourcesRead("skill://pm/core/SKILL.md", response -> assertTrue(
                        response.contents().getFirst().asText().text().contains("Follow the offered links.")))
                .resourcesRead(EXAMPLES, response -> assertTrue(
                        response.contents().getFirst().asText().text().startsWith("Example")))
                .thenAssertResults();

        assertTrue(manifests.size() >= 3);
        var core = manifests.getJsonObject(0);
        assertEquals("core", core.getString("name"));
        assertTrue(core.getJsonArray("files").getJsonObject(0).getString("digest").startsWith("sha256:"));
        var reads = events("skill_read");
        assertTrue(reads.stream().anyMatch(event -> "tool".equals(event.getString("via"))
                && "w9".equals(event.getString("connection"))));
        assertTrue(reads.stream().anyMatch(event -> "resource".equals(event.getString("via"))
                && EXAMPLES.equals(event.getString("uri")) && event.getBoolean("found")));
        assertTrue(reads.stream().anyMatch(event -> !event.getBoolean("found")));
    }
}
