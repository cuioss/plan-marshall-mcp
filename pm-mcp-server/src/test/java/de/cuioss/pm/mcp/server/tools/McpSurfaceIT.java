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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Gates 10 and 11 against the packaged daemon (JVM runner or native binary): sessionless MCP in both host forms,
 * the flat tool schemas, a core tool call, and the refusal of a job token while no job exists.
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
    @DisplayName("refuses a job token no job is bound to")
    void shouldRefuseUnknownJobToken() throws Exception {
        var worker = Map.of("PM-MCP-Job-Token", TestSecrets.JOB_TOKEN, "PM-MCP-Generation", TestSecrets.JOB_ID);

        var response = send(worker, TestRuntime.statelessMessage(4, "tools/list", new JsonObject()));

        assertEquals(401, response.status(), response.body());
    }
}
