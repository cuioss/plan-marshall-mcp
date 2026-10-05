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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;


import de.cuioss.pm.mcp.server.test.DaemonProcess;
import de.cuioss.pm.mcp.server.test.TestBases;
import de.cuioss.pm.mcp.server.test.TestRuntime;
import de.cuioss.pm.mcp.server.test.TestSecrets;
import de.cuioss.pm.mcp.server.test.ToolSchemaRules;
import de.cuioss.pm.mcp.server.test.UdsHttp;
import de.cuioss.pm.mcp.server.test.VerificationResults;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gates 1, 10 and 11 against the packaged daemon (JVM runner or native binary): sessionless MCP in both host
 * forms, the connection metadata in the tool handler, progress and elicitation on the call's own stream, the
 * flat tool schemas, and the ingestion validator ({@code commonmark}, {@code cui-http}) inside the image.
 */
@DisplayName("MCP surface of the packaged daemon")
class McpSurfaceIT {

    private static Path base;
    private static DaemonProcess daemon;

    @BeforeAll
    static void start() throws IOException {
        base = TestBases.create("pmm");
        daemon = DaemonProcess.startReady(base);
    }

    @AfterAll
    static void stop() throws IOException {
        daemon.close();
        TestBases.delete(base);
    }

    private static UdsHttp.Response send(Map<String, String> credentials, JsonObject message) throws IOException {
        return TestRuntime.mcp(daemon.paths(), credentials, message);
    }

    private static JsonObject call(Map<String, String> credentials, String tool, JsonObject params)
            throws IOException {
        var response = send(credentials, TestRuntime.statelessMessage(5, "tools/call",
                params.copy().put("name", tool).put("arguments", params.getJsonObject("arguments", new JsonObject()))));
        assertEquals(200, response.status(), response.body());
        assertNull(response.headers().get("mcp-session-id"));
        return TestRuntime.result(response.body()).getJsonObject("result");
    }

    private static String text(JsonObject result) {
        return result.getJsonArray("content").getJsonObject(0).getString("text");
    }

    @Test
    @DisplayName("serves both host forms without a session and lists closed flat schemas")
    void shouldServeSessionless() throws Exception {
        var runtime = TestRuntime.bearer(daemon.token());
        var discover = send(runtime, TestRuntime.statelessMessage(1, "server/discover", new JsonObject()));
        assertEquals(200, discover.status(), discover.body());
        assertNull(discover.headers().get("mcp-session-id"));

        var initialize = new JsonObject().put("jsonrpc", "2.0").put("id", 2).put("method", "initialize")
                .put("params", new JsonObject().put("protocolVersion", "2025-11-25")
                        .put("capabilities", new JsonObject())
                        .put("clientInfo", new JsonObject().put("name", "opencode").put("version", "1")));
        var init = UdsHttp.request(daemon.paths().socket(), "POST", "/mcp",
                TestRuntime.mcpHeaders(runtime, initialize), initialize.encode());
        assertEquals(200, init.status(), init.body());
        assertNull(init.headers().get("mcp-session-id"));

        var tools = TestRuntime.result(send(runtime, TestRuntime.statelessMessage(3, "tools/list",
                new JsonObject())).body()).getJsonObject("result").getJsonArray("tools");
        var names = new ArrayList<String>();
        var violations = new ArrayList<String>();
        for (var entry : tools) {
            var tool = (JsonObject) entry;
            names.add(tool.getString("name"));
            violations.addAll(ToolSchemaRules.violations(tool.getString("name"), tool.getJsonObject("inputSchema")));
        }
        assertTrue(names.containsAll(CoreTools.NAMES), names.toString());
        assertTrue(violations.isEmpty(), violations.toString());
        assertEquals(CoreTools.stubAnswer("pm_plans"), text(call(runtime, "pm_plans", new JsonObject())));
        VerificationResults.write("gate10-sessionless", Map.of("server_discover", discover.status(),
                "initialize_session_id", "none", "tools", names.size()), true);
        VerificationResults.write("gate11-flat-schemas", Map.of("tools", names.size(), "violations",
                violations.size()), violations.isEmpty());
    }

    @Test
    @DisplayName("hands the connection metadata to the tool handler through the security identity")
    void shouldCarryIdentity() throws Exception {
        var session = TestRuntime.bearer(daemon.token());
        session.put("PM-MCP-Client", "claude");
        session.put("PM-MCP-Workspace", "/work/repo");
        var worker = new HashMap<>(Map.of("PM-MCP-Job-Token", TestSecrets.JOB_TOKEN, "PM-MCP-Generation",
                "j-spike0001"));

        var sessionIdentity = new JsonObject(text(call(session, "spike_identity", new JsonObject())));
        var workerIdentity = new JsonObject(text(call(worker, "spike_identity", new JsonObject())));

        assertEquals("claude", sessionIdentity.getJsonObject("attributes").getString("pm.client"));
        assertEquals("/work/repo", sessionIdentity.getJsonObject("attributes").getString("pm.workspace"));
        assertEquals("j-spike0001", workerIdentity.getString("principal"));
        assertFalse(workerIdentity.getJsonObject("attributes").containsKey("pm.client"));
        assertEquals(401, send(Map.of("PM-MCP-Job-Token", "unknown"),
                TestRuntime.statelessMessage(4, "tools/list", new JsonObject())).status());
        VerificationResults.write("gate10-identity-in-handler", Map.of("session_attributes",
                sessionIdentity.getJsonObject("attributes").size(), "worker_principal",
                workerIdentity.getString("principal")), true);
    }

    @Test
    @DisplayName("streams progress on the call's response and elicits by an input_required round trip")
    void shouldStreamProgressAndElicit() throws Exception {
        var runtime = TestRuntime.bearer(daemon.token());
        var progress = send(runtime, TestRuntime.statelessMessage(6, "tools/call", new JsonObject()
                .put("name", "spike_progress").put("arguments", new JsonObject())
                .put("_meta", new JsonObject().put("progressToken", "p-1"))));
        var notifications = progress.body().lines().filter(line -> line.contains("notifications/progress")).count();

        var first = call(runtime, "spike_elicit", new JsonObject());
        var answered = call(runtime, "spike_elicit", new JsonObject().put("inputResponses", new JsonObject()
                .put("choice", new JsonObject().put("action", "accept")
                        .put("content", new JsonObject().put("choice", "issue")))));

        assertEquals(3, notifications);
        assertEquals("input_required", first.getString("resultType"));
        assertEquals("answer: issue", text(answered));
        var values = new LinkedHashMap<String, Object>();
        values.put("progress_notifications", notifications);
        values.put("elicitation", "input_required round trip (no server-initiated request under 2026-07-28)");
        VerificationResults.write("gate10-progress-elicitation", values, true);
    }

    @Test
    @DisplayName("runs the ingestion validator on commonmark and the cui-http URL pipelines")
    void shouldRunIngestionValidator() throws Exception {
        var report = new JsonObject(text(call(TestRuntime.bearer(daemon.token()), "spike_ingest",
                new JsonObject().put("arguments", new JsonObject().put("text",
                        "<div>x</div>\n\n[a](https://example.com/%2e%2e/%2e%2e/etc)")))));

        var kinds = report.getJsonArray("findings").stream().map(JsonObject.class::cast)
                .map(finding -> finding.getString("kind")).toList();
        assertTrue(kinds.contains("html_block"), kinds.toString());
        assertTrue(kinds.contains("link_rejected"), kinds.toString());
        VerificationResults.write("gate1-ingestion-libraries", Map.of("findings", kinds.toString()), true);
    }
}
