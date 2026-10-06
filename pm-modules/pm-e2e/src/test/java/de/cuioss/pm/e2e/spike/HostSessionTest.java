/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Host session configuration of the gate 9 and gate 11 drivers")
class HostSessionTest {

    private static final List<String> RELAY = List.of("/h/bin/pm-mcp", "serve", "--client", "claude");
    private static final Map<String, String> ENV = Map.of("PM_MCP_BASE", "/tmp/b", "PM_MCP_HOME", "/h");

    @Test
    @DisplayName("gives the session relay its base and home through the server's environment entry")
    void shouldPassEnvironment() throws Exception {
        var claude = StubEvent.JSON.readTree(HostSession.claudeConfig(RELAY, ENV)).path("mcpServers")
                .path(HostSession.SERVER);
        var agy = StubEvent.JSON.readTree(HostSession.agyConfig(RELAY, ENV)).path("mcpServers")
                .path(HostSession.SERVER);
        var opencode = StubEvent.JSON.readTree(HostSession.opencodeConfig(RELAY, ENV, List.of("pm_state")));

        assertEquals("/h/bin/pm-mcp", claude.path("command").asText());
        assertEquals("--client", claude.path("args").get(1).asText());
        assertEquals("/tmp/b", claude.path("env").path("PM_MCP_BASE").asText());
        assertFalse(agy.has("type"));
        assertEquals("/h", agy.path("env").path("PM_MCP_HOME").asText());
        var server = opencode.path("mcp").path(HostSession.SERVER);
        assertEquals("/tmp/b", server.path("environment").path("PM_MCP_BASE").asText());
        var tools = opencode.path("agent").path("pm-host").path("tools");
        assertFalse(tools.path("*").asBoolean());
        assertTrue(tools.path("plan-marshall_pm_state").asBoolean());
    }

    @Test
    @DisplayName("names the server tools as each host does")
    void shouldNameTools() {
        assertEquals("mcp__plan-marshall__pm_do", HostSession.CLAUDE.hostToolName("pm_do"));
        assertEquals("plan-marshall_pm_do", HostSession.OPENCODE.hostToolName("pm_do"));
        assertEquals("pm_do", HostSession.ANTIGRAVITY.hostToolName("pm_do"));
        assertEquals("antigravity", HostSession.ANTIGRAVITY.clientId());
    }
}
