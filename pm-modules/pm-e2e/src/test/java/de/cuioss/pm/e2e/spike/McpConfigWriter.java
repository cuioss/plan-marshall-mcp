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

import java.util.ArrayList;
import java.util.List;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The per-worker MCP configuration of each harness. Its only server is the worker relay
 * {@code <PM_MCP_HOME>/bin/pm-mcp serve --job --socket <socket>}; the job token and job id reach the relay through
 * the harness's environment, never through the configuration file (the file stays in the worker directory).
 */
final class McpConfigWriter {

    /** The server name every harness sees; the tool prefixes derive from it. */
    static final String SERVER = "plan-marshall";

    /** The environment variables the relay reads. */
    static final List<String> RELAY_ENV = List.of("PM_MCP_JOB_TOKEN", "PM_MCP_JOB_ID");

    private McpConfigWriter() {
    }

    /**
     * Claude Code: the file passed with {@code --mcp-config} (with {@code --strict-mcp-config}).
     *
     * @param relay the relay command line, absolute program first
     * @return the JSON text
     */
    static String claude(List<String> relay) {
        var root = StubEvent.JSON.createObjectNode();
        var server = root.putObject("mcpServers").putObject(SERVER);
        server.put("type", "stdio");
        server.put("command", relay.getFirst());
        var args = server.putArray("args");
        relay.subList(1, relay.size()).forEach(args::add);
        return write(root);
    }

    /**
     * OpenCode: {@code opencode.json} in the worker directory ({@code --dir}), with the restricted agent
     * {@code pull-worker} of Part A.
     *
     * @param relay     the relay command line, absolute program first
     * @param tools     the stub tools the worker may call
     * @param timeoutMs the MCP request timeout, or {@code null} for OpenCode's default
     * @return the JSON text
     */
    static String opencode(List<String> relay, List<String> tools, Long timeoutMs) {
        var root = StubEvent.JSON.createObjectNode();
        root.put("$schema", "https://opencode.ai/config.json");
        var server = root.putObject("mcp").putObject(SERVER);
        server.put("type", "local");
        var command = server.putArray("command");
        relay.forEach(command::add);
        server.put("enabled", true);
        if (timeoutMs != null) {
            server.put("timeout", timeoutMs);
        }
        var agent = root.putObject("agent").putObject("pull-worker");
        agent.put("description", "Part B harness-driver worker");
        agent.put("mode", "primary");
        ObjectNode allowed = agent.putObject("tools");
        allowed.put("*", false);
        tools.forEach(tool -> allowed.put(SERVER + "_" + tool, true));
        return write(root);
    }

    /**
     * Codex: {@code -c} overrides of {@code codex exec}, so no file in {@code ~/.codex} changes. The relay
     * variables are forwarded from Codex's environment by name ({@code env_vars}).
     *
     * @param relay      the relay command line, absolute program first
     * @param tools      the stub tools the worker may call
     * @param timeoutSec the MCP tool-call timeout, or {@code null} for Codex's default
     * @return the arguments, each {@code -c} followed by its {@code key=value}
     */
    static List<String> codex(List<String> relay, List<String> tools, Long timeoutSec) {
        var prefix = "mcp_servers." + SERVER + ".";
        var arguments = new ArrayList<String>();
        override(arguments, prefix + "command", toml(relay.getFirst()));
        override(arguments, prefix + "args", tomlArray(relay.subList(1, relay.size())));
        override(arguments, prefix + "env_vars", tomlArray(RELAY_ENV));
        override(arguments, prefix + "enabled_tools", tomlArray(tools));
        if (timeoutSec != null) {
            override(arguments, prefix + "tool_timeout_sec", Long.toString(timeoutSec));
        }
        return List.copyOf(arguments);
    }

    /**
     * @param tools the stub tools
     * @return the names Claude Code knows them by
     */
    static List<String> claudeToolNames(List<String> tools) {
        return tools.stream().map(tool -> "mcp__" + SERVER + "__" + tool).toList();
    }

    private static void override(List<String> arguments, String key, String value) {
        arguments.add("-c");
        arguments.add(key + "=" + value);
    }

    static String toml(String value) {
        var escaped = new StringBuilder("\"");
        for (var c : value.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                default -> escaped.append(c);
            }
        }
        return escaped.append('"').toString();
    }

    static String tomlArray(List<String> values) {
        return "[" + String.join(",", values.stream().map(McpConfigWriter::toml).toList()) + "]";
    }

    private static String write(ObjectNode root) {
        try {
            return StubEvent.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
