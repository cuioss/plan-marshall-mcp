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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Path;


import de.cuioss.pm.mcp.server.test.DaemonProcess;
import de.cuioss.pm.mcp.server.test.TestBases;
import de.cuioss.pm.mcp.server.test.TestRuntime;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Talks to the packaged daemon (JVM runner or native binary) over its Unix socket in the relay's sessionless
 * form; McpAssured speaks TCP only, which the daemon does not open.
 */
@DisplayName("MCP hello tool of the packaged daemon")
class HelloToolIT {

    private static Path base;
    private static DaemonProcess daemon;

    @BeforeAll
    static void start() throws IOException {
        base = TestBases.create("pmh");
        daemon = DaemonProcess.startReady(base);
    }

    @AfterAll
    static void stop() throws IOException {
        daemon.close();
        TestBases.delete(base);
    }

    @Test
    @DisplayName("server/discover reports the server name")
    void shouldReportServerName() throws Exception {
        var result = TestRuntime.result(TestRuntime.mcp(daemon.paths(), TestRuntime.bearer(daemon.token()),
                TestRuntime.statelessMessage(1, "server/discover", new JsonObject())).body());

        assertEquals("plan-marshall-mcp", result.getJsonObject("result").getJsonObject("_meta")
                .getJsonObject("io.modelcontextprotocol/serverInfo").getString("name"));
    }

    @Test
    @DisplayName("tools/list exposes the hello tool")
    void shouldListHelloTool() throws Exception {
        var tools = TestRuntime.result(TestRuntime.mcp(daemon.paths(), TestRuntime.bearer(daemon.token()),
                TestRuntime.statelessMessage(2, "tools/list", new JsonObject())).body())
                .getJsonObject("result").getJsonArray("tools");

        var hello = tools.stream().map(JsonObject.class::cast).filter(tool -> "hello".equals(tool.getString("name")))
                .findFirst().orElse(null);
        assertNotNull(hello);
        assertEquals("Returns a greeting for the given name.", hello.getString("description"));
    }

    @Test
    @DisplayName("tools/call hello returns the greeting")
    void shouldGreetViaMcp() throws Exception {
        var result = TestRuntime.result(TestRuntime.mcp(daemon.paths(), TestRuntime.bearer(daemon.token()),
                TestRuntime.statelessMessage(3, "tools/call", new JsonObject().put("name", "hello")
                        .put("arguments", new JsonObject().put("name", "Integration")))).body());

        assertEquals("Hello, Integration!", result.getJsonObject("result").getJsonArray("content")
                .getJsonObject(0).getString("text"));
    }
}
