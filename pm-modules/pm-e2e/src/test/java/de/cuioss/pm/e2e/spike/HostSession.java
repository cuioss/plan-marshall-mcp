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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A host session (not a worker) of a harness CLI, started headless with the session relay
 * {@code <PM_MCP_HOME>/bin/pm-mcp serve --client <id>} as its only MCP server, in front of the staged daemon
 * ({@code PM_MCP_BASE} and {@code PM_MCP_HOME} reach the relay through the server's environment entry).
 * <ul>
 * <li>Claude Code: {@code claude -p … --mcp-config <file> --strict-mcp-config --allowedTools … --output-format
 * stream-json --verbose}.</li>
 * <li>OpenCode: {@code opencode run --format json --agent pm-host --dir <dir> …} with {@code opencode.json} in the
 * session directory.</li>
 * <li>Antigravity: {@code agy -p … --output-format stream-json --dangerously-skip-permissions} in the session
 * directory. Antigravity has one global MCP configuration ({@code ~/.gemini/config/mcp_config.json}); the session
 * replaces it and {@link #restore()} puts the original back.</li>
 * <li>Codex: not run in Part B on macOS (operator decision); only the id exists.</li>
 * </ul>
 * Opt-in property of the ITs that use it: {@code spike.harness=claude|opencode|antigravity|codex}.
 */
enum HostSession {

    /** Claude Code. */
    CLAUDE("claude", "claude", "claude-haiku-4-5"),
    /** OpenCode. */
    OPENCODE("opencode", "opencode", "opencode/space-bunny-free"),
    /** Antigravity. */
    ANTIGRAVITY("antigravity", "agy", "gemini-3.8-flash-low"),
    /** Codex (not run on macOS). */
    CODEX("codex", "codex", null);

    /** The pattern of {@code spike.harness} that enables the host-session ITs. */
    static final String HOSTS = "claude|opencode|antigravity|codex";
    /** The MCP server name every host sees. */
    static final String SERVER = McpConfigWriter.SERVER;

    private static final Path AGY_CONFIG = Path.of(System.getProperty("user.home"), ".gemini", "config",
            "mcp_config.json");

    private final String clientId;
    private final String executable;
    private final String defaultModel;

    HostSession(String clientId, String executable, String defaultModel) {
        this.clientId = clientId;
        this.executable = executable;
        this.defaultModel = defaultModel;
    }

    /** @return the host of {@code spike.harness} */
    static HostSession fromSystemProperties() {
        var name = System.getProperty(SpikeSettings.HARNESS, "claude").strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(host -> host.clientId.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown host " + name));
    }

    /** @return the model of {@code spike.model}, or the default of the host */
    String model() {
        return System.getProperty("spike.model", defaultModel);
    }

    /** @return the client id of {@code pm-mcp serve --client} */
    String clientId() {
        return clientId;
    }

    /** @return the absolute path of the host executable on {@code PATH} */
    Optional<Path> locate() {
        var path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        return Arrays.stream(path.split(File.pathSeparator)).map(dir -> Path.of(dir, executable))
                .filter(Files::isExecutable).findFirst().map(Path::toAbsolutePath);
    }

    /** @return the first line of {@code <host> --version} */
    String version() {
        var exe = locate();
        if (exe.isEmpty()) {
            return "not installed";
        }
        try {
            var process = new ProcessBuilder(exe.get().toString(), "--version").redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor(30, TimeUnit.SECONDS);
            return out.lines().findFirst().orElse("unknown");
        } catch (IOException e) {
            return "unknown: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    /**
     * @param tool a server tool name
     * @return the name the host gives the tool
     */
    String hostToolName(String tool) {
        return switch (this) {
            case CLAUDE -> "mcp__" + SERVER + "__" + tool;
            case OPENCODE -> SERVER + "_" + tool;
            case ANTIGRAVITY, CODEX -> tool;
        };
    }

    /**
     * Writes the session's MCP configuration and starts the host.
     *
     * @param stage      the staged installation
     * @param base       {@code PM_MCP_BASE} of the running daemon
     * @param sessionDir the session directory (outside every repository)
     * @param prompt     the prompt
     * @param tools      the server tools the host may call
     * @param logFile    the arrival-stamped output log
     * @return the running host
     * @throws IOException if the host is not installed or cannot start
     */
    Worker start(SpikeStage stage, Path base, Path sessionDir, String prompt, List<String> tools, Path logFile)
            throws IOException {
        if (this == CODEX) {
            throw new IOException("Codex host sessions are not run (operator decision)");
        }
        var exe = locate().orElseThrow(() -> new IOException(executable + " is not on PATH"));
        Files.createDirectories(sessionDir);
        var relay = List.of(stage.relay().toString(), "serve", "--client", clientId);
        var env = Map.of("PM_MCP_HOME", stage.home().toString(), "PM_MCP_BASE", base.toString());
        var command = new ArrayList<String>();
        command.add(exe.toString());
        switch (this) {
            case CLAUDE -> {
                var config = sessionDir.resolve("mcp-config.json");
                Files.writeString(config, claudeConfig(relay, env));
                command.addAll(List.of("-p", prompt, "--mcp-config", config.toString(), "--strict-mcp-config",
                        "--allowedTools", String.join(",", tools.stream().map(this::hostToolName).toList()),
                        "--output-format", "stream-json", "--verbose", "--model", model()));
            }
            case OPENCODE -> {
                Files.writeString(sessionDir.resolve("opencode.json"), opencodeConfig(relay, env, tools));
                command.addAll(List.of("run", "--format", "json", "--agent", "pm-host", "-m", model(), "--dir",
                        sessionDir.toString(), prompt));
            }
            case ANTIGRAVITY -> {
                backupAgyConfig();
                Files.writeString(AGY_CONFIG, agyConfig(relay, env));
                command.addAll(List.of("-p", prompt, "--output-format", "stream-json", "--model", model(),
                        "--dangerously-skip-permissions"));
            }
            case CODEX -> throw new IllegalStateException("unreachable");
        }
        var builder = new ProcessBuilder(command).directory(sessionDir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        var environment = builder.environment();
        var inherited = new HashMap<>(environment);
        environment.clear();
        inherited.forEach((name, value) -> {
            if (Harness.HOST_SESSION_PREFIXES.stream().noneMatch(name::startsWith)
                    && !name.startsWith("PM_MCP_JOB")) {
                environment.put(name, value);
            }
        });
        environment.putAll(env);
        return new Worker(clientId, 1, "host", "host-session", null, builder.start(), sessionDir, logFile);
    }

    /**
     * @param sinceMs the session start
     * @return the MCP-related lines of the host's own log written since the start, where the host keeps one
     *         (Antigravity: {@code ~/.gemini/antigravity-cli/cli.log}), at most 40
     */
    List<String> diagnostics(long sinceMs) {
        if (this != ANTIGRAVITY) {
            return List.of();
        }
        var log = Path.of(System.getProperty("user.home"), ".gemini", "antigravity-cli", "cli.log");
        try {
            if (!Files.exists(log) || Files.getLastModifiedTime(log).toMillis() < sinceMs) {
                return List.of();
            }
            var lines = Files.readAllLines(log).stream()
                    .filter(line -> line.toLowerCase(Locale.ROOT).contains("mcp")
                            || line.contains(SERVER)).toList();
            return lines.subList(Math.max(0, lines.size() - 40), lines.size()).stream()
                    .map(line -> line.length() > 600 ? line.substring(0, 600) + "…" : line).toList();
        } catch (IOException e) {
            return List.of("unreadable: " + e.getMessage());
        }
    }

    /** Puts Antigravity's original global MCP configuration back; nothing for the other hosts. */
    void restore() throws IOException {
        if (this != ANTIGRAVITY) {
            return;
        }
        var backup = agyBackup();
        if (Files.exists(backup)) {
            Files.move(backup, AGY_CONFIG, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path agyBackup() {
        return AGY_CONFIG.resolveSibling("mcp_config.json.pm-spike-backup");
    }

    private static void backupAgyConfig() throws IOException {
        var backup = agyBackup();
        if (Files.exists(AGY_CONFIG) && !Files.exists(backup)) {
            Files.copy(AGY_CONFIG, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    static String claudeConfig(List<String> relay, Map<String, String> env) {
        var root = StubEvent.JSON.createObjectNode();
        var server = root.putObject("mcpServers").putObject(SERVER);
        server.put("type", "stdio");
        stdioServer(server, relay, env);
        return write(root);
    }

    static String agyConfig(List<String> relay, Map<String, String> env) {
        var root = StubEvent.JSON.createObjectNode();
        stdioServer(root.putObject("mcpServers").putObject(SERVER), relay, env);
        return write(root);
    }

    static String opencodeConfig(List<String> relay, Map<String, String> env, List<String> tools) {
        var root = StubEvent.JSON.createObjectNode();
        root.put("$schema", "https://opencode.ai/config.json");
        var server = root.putObject("mcp").putObject(SERVER);
        server.put("type", "local");
        var command = server.putArray("command");
        relay.forEach(command::add);
        server.put("enabled", true);
        var environment = server.putObject("environment");
        env.forEach(environment::put);
        var agent = root.putObject("agent").putObject("pm-host");
        agent.put("description", "Part B host session");
        agent.put("mode", "primary");
        var allowed = agent.putObject("tools");
        allowed.put("*", false);
        tools.forEach(tool -> allowed.put(SERVER + "_" + tool, true));
        return write(root);
    }

    private static void stdioServer(ObjectNode server, List<String> relay, Map<String, String> env) {
        server.put("command", relay.getFirst());
        var args = server.putArray("args");
        relay.subList(1, relay.size()).forEach(args::add);
        var environment = server.putObject("env");
        env.forEach(environment::put);
    }

    private static String write(ObjectNode root) {
        try {
            return StubEvent.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
