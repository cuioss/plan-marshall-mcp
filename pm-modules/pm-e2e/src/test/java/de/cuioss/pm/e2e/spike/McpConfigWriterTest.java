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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Per-worker MCP configuration of the harnesses")
class McpConfigWriterTest {

    private static final List<String> RELAY = List.of("/opt/pm/bin/pm-mcp", "serve", "--job", "--socket",
            "/b/run/runtime.sock");
    private static final List<String> TOOLS = List.of("pull_wait", "pull_submit", "pull_ack");

    @Nested
    @DisplayName("Claude Code")
    class Claude {

        @Test
        @DisplayName("names the worker relay as the only stdio server, without the job token")
        void shouldNameRelayOnly() throws Exception {
            var json = StubEvent.JSON.readTree(McpConfigWriter.claude(RELAY));

            var servers = json.path("mcpServers");
            assertEquals(1, servers.size());
            var server = servers.path(McpConfigWriter.SERVER);
            assertEquals("stdio", server.path("type").asText());
            assertEquals("/opt/pm/bin/pm-mcp", server.path("command").asText());
            assertEquals("[\"serve\",\"--job\",\"--socket\",\"/b/run/runtime.sock\"]", server.path("args").toString());
            assertFalse(json.toString().contains("PM_MCP_JOB_TOKEN"));
        }

        @Test
        @DisplayName("names the allowed tools with the server prefix")
        void shouldPrefixTools() {
            assertEquals(List.of("mcp__plan-marshall__pull_wait", "mcp__plan-marshall__pull_submit",
                    "mcp__plan-marshall__pull_ack"), McpConfigWriter.claudeToolNames(TOOLS));
        }
    }

    @Nested
    @DisplayName("OpenCode")
    class OpenCode {

        @Test
        @DisplayName("defines the local server and the restricted agent pull-worker of Part A")
        void shouldDefineAgent() throws Exception {
            var json = StubEvent.JSON.readTree(McpConfigWriter.opencode(RELAY, TOOLS, 7_200_000L));

            var server = json.path("mcp").path(McpConfigWriter.SERVER);
            assertEquals("local", server.path("type").asText());
            assertEquals(RELAY.size(), server.path("command").size());
            assertEquals(7_200_000L, server.path("timeout").asLong());
            var tools = json.path("agent").path("pull-worker").path("tools");
            assertFalse(tools.path("*").asBoolean());
            assertTrue(tools.path("plan-marshall_pull_ack").asBoolean());
            assertEquals(4, tools.size());
        }

        @Test
        @DisplayName("keeps OpenCode's default timeout when none is given")
        void shouldOmitTimeout() throws Exception {
            var json = StubEvent.JSON.readTree(McpConfigWriter.opencode(RELAY, TOOLS, null));

            assertFalse(json.path("mcp").path(McpConfigWriter.SERVER).has("timeout"));
        }
    }

    @Nested
    @DisplayName("Codex")
    class Codex {

        @Test
        @DisplayName("writes -c overrides that forward the relay variables by name")
        void shouldWriteOverrides() {
            var arguments = McpConfigWriter.codex(RELAY, TOOLS, 3600L);

            assertEquals(List.of("-c", "mcp_servers.plan-marshall.command=\"/opt/pm/bin/pm-mcp\"",
                    "-c", "mcp_servers.plan-marshall.args=[\"serve\",\"--job\",\"--socket\",\"/b/run/runtime.sock\"]",
                    "-c", "mcp_servers.plan-marshall.env_vars=[\"PM_MCP_JOB_TOKEN\",\"PM_MCP_JOB_ID\"]",
                    "-c", "mcp_servers.plan-marshall.enabled_tools=[\"pull_wait\",\"pull_submit\",\"pull_ack\"]",
                    "-c", "mcp_servers.plan-marshall.tool_timeout_sec=3600"), arguments);
        }

        @Test
        @DisplayName("escapes TOML strings")
        void shouldEscape() {
            assertEquals("\"a\\\"b\\\\c\\n\"", McpConfigWriter.toml("a\"b\\c\n"));
        }
    }

    @Nested
    @DisplayName("harness command lines")
    class Commands {

        @TempDir
        Path dir;

        private Harness.WorkerSpec spec(String schema) {
            return new Harness.WorkerSpec(dir.resolve("w1-g1"), "do it", "m1", RELAY, TOOLS, schema, null);
        }

        @Test
        @DisplayName("Claude Code: headless with strict MCP configuration and stream-json, as in Part A")
        void shouldBuildClaude() throws Exception {
            var command = Harness.CLAUDE.command(Path.of("/bin/claude"), spec("{}"));

            assertEquals(List.of("/bin/claude", "-p", "do it", "--mcp-config",
                    dir.resolve("w1-g1/mcp-config.json").toString(), "--strict-mcp-config", "--allowedTools",
                    "mcp__plan-marshall__pull_wait,mcp__plan-marshall__pull_submit,mcp__plan-marshall__pull_ack",
                    "--output-format", "stream-json", "--verbose", "--json-schema", "{}", "--model", "m1"), command);
            assertTrue(Files.exists(dir.resolve("w1-g1/mcp-config.json")));
        }

        @Test
        @DisplayName("OpenCode: run with the agent pull-worker in the worker directory")
        void shouldBuildOpenCode() throws Exception {
            var command = Harness.OPENCODE.command(Path.of("/bin/opencode"), spec(null));

            assertEquals(List.of("/bin/opencode", "run", "--format", "json", "--agent", "pull-worker", "-m", "m1",
                    "--dir", dir.resolve("w1-g1").toString(), "do it"), command);
            assertTrue(Files.exists(dir.resolve("w1-g1/opencode.json")));
        }

        @Test
        @DisplayName("Codex: exec with JSON events, overrides and an output schema file")
        void shouldBuildCodex() throws Exception {
            var command = Harness.CODEX.command(Path.of("/bin/codex"), spec("{\"type\":\"object\"}"));

            assertEquals(List.of("/bin/codex", "exec", "--json", "--skip-git-repo-check", "--sandbox", "read-only",
                    "-C", dir.resolve("w1-g1").toString(), "-c", "approval_policy=\"never\""), command.subList(0, 10));
            assertTrue(command.contains("--output-schema"));
            assertEquals("do it", command.getLast());
        }

        @Test
        @DisplayName("drops the driver's own host-session variables and sets Claude Code's timeout")
        void shouldFilterEnvironment() {
            var env = Harness.CLAUDE.environment(Map.of("CLAUDECODE", "1", "CLAUDE_CODE_SESSION_ID", "x", "HOME",
                    "/h"), 60_000L);

            assertEquals(Map.of("HOME", "/h", "MCP_TOOL_TIMEOUT", "60000"), env);
        }
    }
}
