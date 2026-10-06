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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a headless harness reported on {@code stdout}: whether every line parses as JSON, the session, the final
 * answer, its structured form, and the usage and cost fields.
 *
 * @param lines      the number of non-empty lines
 * @param nonJson    the number of lines that are no JSON object
 * @param resultSeen whether the final result event was seen
 * @param sessionId  the session or thread id, may be {@code null}
 * @param finalText  the final answer, may be {@code null}
 * @param structured the final answer as JSON (the schema-validated output where the harness has one), may be
 *                   {@code null}
 * @param usage      the token counts by field name, summed over the run
 * @param costUsd    the cost, may be {@code null}
 * @param mcpTools   the MCP tools the harness listed at start, where it reports them
 */
record HarnessOutput(int lines, int nonJson, boolean resultSeen, String sessionId, String finalText,
JsonNode structured, Map<String, Long> usage, Double costUsd, List<String> mcpTools) {

    /**
     * @param harness the harness
     * @param stdout  the lines of its standard output
     * @return the parsed output
     */
    static HarnessOutput parse(Harness harness, List<String> stdout) {
        var builder = new Builder();
        for (var line : stdout) {
            if (line.isBlank()) {
                continue;
            }
            builder.lines++;
            JsonNode node;
            try {
                node = StubEvent.JSON.readTree(line);
            } catch (IOException _) {
                node = null;
            }
            if (node == null || !node.isObject()) {
                builder.nonJson++;
                continue;
            }
            switch (harness) {
                case CLAUDE -> builder.claude(node);
                case OPENCODE -> builder.opencode(node);
                case CODEX -> builder.codex(node);
            }
        }
        if (builder.structured == null && builder.finalText != null) {
            builder.structured = json(builder.finalText);
        }
        return new HarnessOutput(builder.lines, builder.nonJson, builder.resultSeen, builder.sessionId,
                builder.finalText, builder.structured, Map.copyOf(builder.usage), builder.cost,
                List.copyOf(builder.tools));
    }

    /** @return the figures for the result file */
    Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("lines", lines);
        map.put("non_json_lines", nonJson);
        map.put("result_seen", resultSeen);
        map.put("session_id", sessionId);
        map.put("final_text", finalText == null || finalText.length() <= 400 ? finalText : finalText.substring(0, 400));
        map.put("structured_output_parses", structured != null && structured.isObject());
        map.put("usage", usage);
        map.put("cost_usd", costUsd);
        map.put("mcp_tools", mcpTools);
        return map;
    }

    private static JsonNode json(String text) {
        var trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[a-z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        try {
            var node = StubEvent.JSON.readTree(trimmed);
            return node != null && node.isObject() ? node : null;
        } catch (IOException _) {
            return null;
        }
    }

    private static final class Builder {

        private int lines;
        private int nonJson;
        private boolean resultSeen;
        private String sessionId;
        private String finalText;
        private JsonNode structured;
        private Double cost;
        private final Map<String, Long> usage = new LinkedHashMap<>();
        private final List<String> tools = new ArrayList<>();

        private void claude(JsonNode node) {
            var type = node.path("type").asText();
            if (node.hasNonNull("session_id")) {
                sessionId = node.get("session_id").asText();
            }
            if ("system".equals(type) && "init".equals(node.path("subtype").asText())) {
                node.path("tools").forEach(tool -> {
                    if (tool.asText().startsWith("mcp__")) {
                        tools.add(tool.asText());
                    }
                });
            }
            if ("result".equals(type) || node.has("total_cost_usd") && node.has("usage")) {
                resultSeen = true;
                if (node.hasNonNull("result")) {
                    finalText = node.get("result").asText();
                }
                if (node.hasNonNull("structured_output")) {
                    structured = node.get("structured_output");
                }
                if (node.hasNonNull("total_cost_usd")) {
                    cost = node.get("total_cost_usd").asDouble();
                }
                var counts = node.path("usage");
                for (var field : List.of("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens",
                        "output_tokens")) {
                    if (counts.has(field)) {
                        usage.put(field, counts.get(field).asLong());
                    }
                }
            }
        }

        private void opencode(JsonNode node) {
            var type = node.path("type").asText();
            if (node.hasNonNull("sessionID")) {
                sessionId = node.get("sessionID").asText();
            }
            var part = node.path("part");
            if ("text".equals(type) && part.hasNonNull("text")) {
                finalText = part.get("text").asText();
            }
            if ("step_finish".equals(type)) {
                if ("stop".equals(part.path("reason").asText())) {
                    resultSeen = true;
                }
                var tokens = part.path("tokens");
                add("input", tokens.path("input"));
                add("output", tokens.path("output"));
                add("reasoning", tokens.path("reasoning"));
                add("cache_read", tokens.path("cache").path("read"));
                add("cache_write", tokens.path("cache").path("write"));
                if (part.has("cost")) {
                    cost = (cost == null ? 0 : cost) + part.get("cost").asDouble();
                }
            }
            if ("tool_use".equals(type) && part.hasNonNull("tool") && !tools.contains(part.get("tool").asText())) {
                tools.add(part.get("tool").asText());
            }
        }

        private void codex(JsonNode node) {
            var type = node.path("type").asText();
            if ("thread.started".equals(type)) {
                sessionId = node.path("thread_id").asText(null);
            }
            var item = node.path("item");
            if ("item.completed".equals(type) && "agent_message".equals(item.path("type").asText())) {
                finalText = item.path("text").asText();
            }
            if ("item.completed".equals(type) && "mcp_tool_call".equals(item.path("type").asText())) {
                var tool = item.path("server").asText() + "." + item.path("tool").asText();
                if (!tools.contains(tool)) {
                    tools.add(tool);
                }
            }
            if ("turn.completed".equals(type)) {
                resultSeen = true;
                var counts = node.path("usage");
                add("input_tokens", counts.path("input_tokens"));
                add("cached_input_tokens", counts.path("cached_input_tokens"));
                add("output_tokens", counts.path("output_tokens"));
            }
        }

        private void add(String field, JsonNode value) {
            if (value.isNumber()) {
                usage.merge(field, value.asLong(), Long::sum);
            }
        }
    }
}
