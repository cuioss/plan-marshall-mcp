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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Parsing of headless harness output")
class HarnessOutputTest {

    @Test
    @DisplayName("Claude Code: session, MCP tools, structured output, usage and cost of the result event")
    void shouldParseClaude() {
        var output = HarnessOutput.parse(Harness.CLAUDE, List.of(
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\",\"tools\":[\"Read\",\"mcp__plan-marshall__pull_wait\"]}",
                "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s1\",\"result\":\"{}\","
                        + "\"structured_output\":{\"status\":\"done\"},\"total_cost_usd\":0.04,"
                        + "\"usage\":{\"input_tokens\":26,\"cache_creation_input_tokens\":15751,"
                        + "\"cache_read_input_tokens\":118476,\"output_tokens\":496}}"));

        assertTrue(output.resultSeen());
        assertEquals("s1", output.sessionId());
        assertEquals("done", output.structured().path("status").asText());
        assertEquals(Map.of("input_tokens", 26L, "cache_creation_input_tokens", 15751L, "cache_read_input_tokens",
                118476L, "output_tokens", 496L), output.usage());
        assertEquals(0.04, output.costUsd());
        assertEquals(List.of("mcp__plan-marshall__pull_wait"), output.mcpTools());
        assertEquals(0, output.nonJson());
    }

    @Test
    @DisplayName("OpenCode: token counts summed over the steps of the Part A event shape")
    void shouldParseOpenCode() {
        var step = "{\"type\":\"step_finish\",\"sessionID\":\"ses_1\",\"part\":{\"reason\":\"%s\",\"tokens\":"
                + "{\"input\":273,\"output\":3,\"reasoning\":0,\"cache\":{\"write\":0,\"read\":4224}},\"cost\":0}}";
        var output = HarnessOutput.parse(Harness.OPENCODE, List.of(step.formatted("tool-calls"),
                "{\"type\":\"text\",\"sessionID\":\"ses_1\",\"part\":{\"text\":\"{\\\"status\\\":\\\"done\\\"}\"}}",
                step.formatted("stop")));

        assertTrue(output.resultSeen());
        assertEquals("ses_1", output.sessionId());
        assertEquals(546L, output.usage().get("input"));
        assertEquals(8448L, output.usage().get("cache_read"));
        assertEquals("done", output.structured().path("status").asText());
    }

    @Test
    @DisplayName("Codex: thread id, final agent message and turn usage of exec --json")
    void shouldParseCodex() {
        var output = HarnessOutput.parse(Harness.CODEX, List.of("{\"type\":\"thread.started\",\"thread_id\":\"th1\"}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"mcp_tool_call\",\"server\":\"plan-marshall\","
                        + "\"tool\":\"pull_wait\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"```json\\n{\\\"status\\\":\\\"done\\\"}\\n```\"}}",
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,\"cached_input_tokens\":4,\"output_tokens\":2}}"));

        assertTrue(output.resultSeen());
        assertEquals("th1", output.sessionId());
        assertEquals("done", output.structured().path("status").asText());
        assertEquals(List.of("plan-marshall.pull_wait"), output.mcpTools());
        assertEquals(2L, output.usage().get("output_tokens"));
    }

    @Test
    @DisplayName("counts lines that are no JSON and finds no result in them")
    void shouldCountNonJson() {
        var output = HarnessOutput.parse(Harness.CLAUDE, List.of("plain text", "", "[1]"));

        assertEquals(2, output.lines());
        assertEquals(2, output.nonJson());
        assertFalse(output.resultSeen());
        assertNull(output.structured());
        assertFalse((Boolean) output.toMap().get("structured_output_parses"));
    }

    @Test
    @DisplayName("recognises the end of a tool call in each harness's output")
    void shouldRecognizeToolResult() {
        assertTrue(Harness.CLAUDE.reportsToolResult("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\"}]}}"));
        assertTrue(Harness.OPENCODE.reportsToolResult("{\"type\":\"tool_use\",\"part\":{}}"));
        assertTrue(Harness.CODEX.reportsToolResult("{\"type\":\"item.completed\",\"item\":{\"type\":\"mcp_tool_call\"}}"));
        assertFalse(Harness.CODEX.reportsToolResult("{\"type\":\"item.started\",\"item\":{\"type\":\"mcp_tool_call\"}}"));
    }
}
