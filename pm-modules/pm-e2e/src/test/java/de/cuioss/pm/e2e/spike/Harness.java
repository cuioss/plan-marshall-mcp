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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The harness CLIs a worker runs in, with their headless launch template and per-call MCP configuration.
 * <ul>
 * <li>Claude Code: {@code claude -p <prompt> --mcp-config <file> --strict-mcp-config --allowedTools … --output-format
 * stream-json --verbose --model …}, as in Part A.</li>
 * <li>OpenCode: {@code opencode run --format json --agent pull-worker -m … --dir <worker dir> <prompt>} with
 * {@code opencode.json} in the worker directory, as in Part A.</li>
 * <li>Codex: {@code codex exec --json --skip-git-repo-check -C <worker dir> -c mcp_servers.… <prompt>}.</li>
 * </ul>
 */
enum Harness {

    /** Claude Code. */
    CLAUDE("claude", "claude-haiku-4-5", 2400,
        List.of(".claude", ".claude.json", ".claude.json.backup", ".cache/claude", ".cache/claude-cli-nodejs",
                ".local/state/claude", ".local/share/claude", ".config/claude"),
        List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN")),
    /** OpenCode. */
    OPENCODE("opencode", "opencode/space-bunny-free", 900,
            List.of(".local/share/opencode", ".local/state/opencode", ".cache/opencode", ".config/opencode"),
            List.of("OPENCODE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY")),
    /** Codex. */
    CODEX("codex", null, 900, List.of(".codex"), List.of("OPENAI_API_KEY", "CODEX_API_KEY"));

    /** Environment variables of the driver's own host session, never passed to a worker. */
    static final List<String> HOST_SESSION_PREFIXES = List.of("CLAUDECODE", "CLAUDE_PID", "CLAUDE_EFFORT",
            "CLAUDE_CODE_SSE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_MESSAGING", "CLAUDE_CODE_BRIDGE",
            "CLAUDE_CODE_EXECPATH", "CLAUDE_CODE_SESSION", "CLAUDE_CODE_CHILD", "OPENCODE_SESSION", "CODEX_SANDBOX");

    private final String executable;
    private final String defaultModel;
    private final int idleMaxSeconds;
    private final List<String> stateDirs;
    private final List<String> credentialEnv;

    Harness(String executable, String defaultModel, int idleMaxSeconds, List<String> stateDirs,
            List<String> credentialEnv) {
        this.executable = executable;
        this.defaultModel = defaultModel;
        this.idleMaxSeconds = idleMaxSeconds;
        this.stateDirs = stateDirs;
        this.credentialEnv = credentialEnv;
    }

    /**
     * What one worker gets.
     *
     * @param workerDir     the worker directory (outside every repository)
     * @param prompt        the prompt
     * @param model         the model, may be {@code null} for the harness default
     * @param relay         the relay command line, absolute program first
     * @param tools         the stub tools the worker may call
     * @param jsonSchema    a JSON schema of the final answer, may be {@code null}
     * @param timeoutMillis the MCP call timeout to configure, {@code null} for the harness default
     */
    record WorkerSpec(Path workerDir, String prompt, String model, List<String> relay, List<String> tools,
    String jsonSchema, Long timeoutMillis) {
    }

    static Harness of(String name) {
        return Arrays.stream(values()).filter(harness -> harness.executable.equals(name.strip().toLowerCase(Locale.ROOT)))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown harness " + name));
    }

    String id() {
        return executable;
    }

    String defaultModel() {
        return defaultModel;
    }

    int idleMaxSeconds() {
        return idleMaxSeconds;
    }

    List<String> credentialEnv() {
        return credentialEnv;
    }

    /**
     * @param home the user's home
     * @return the harness's own state paths that exist (its login lives there)
     */
    List<Path> stateDirs(Path home) {
        return stateDirs.stream().map(home::resolve).filter(Files::exists).toList();
    }

    /** @return the absolute path of the harness executable on {@code PATH} */
    Optional<Path> locate() {
        var path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        return Arrays.stream(path.split(File.pathSeparator)).map(dir -> Path.of(dir, executable))
                .filter(Files::isExecutable).findFirst().map(Path::toAbsolutePath);
    }

    /** @return the output of {@code <harness> --version}, or {@code unknown} */
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
     * Writes the worker's configuration into its directory and builds the harness command line.
     *
     * @param executablePath the absolute harness executable
     * @param spec           the worker
     * @return the arguments, harness executable first
     * @throws IOException if a configuration file cannot be written
     */
    List<String> command(Path executablePath, WorkerSpec spec) throws IOException {
        Files.createDirectories(spec.workerDir());
        var command = new ArrayList<String>();
        command.add(executablePath.toString());
        switch (this) {
            case CLAUDE -> {
                var config = spec.workerDir().resolve("mcp-config.json");
                Files.writeString(config, McpConfigWriter.claude(spec.relay()));
                command.addAll(List.of("-p", spec.prompt(), "--mcp-config", config.toString(), "--strict-mcp-config",
                        "--allowedTools", String.join(",", McpConfigWriter.claudeToolNames(spec.tools())),
                        "--output-format", "stream-json", "--verbose"));
                if (spec.jsonSchema() != null) {
                    command.addAll(List.of("--json-schema", spec.jsonSchema()));
                }
                if (spec.model() != null) {
                    command.addAll(List.of("--model", spec.model()));
                }
            }
            case OPENCODE -> {
                Files.writeString(spec.workerDir().resolve("opencode.json"),
                        McpConfigWriter.opencode(spec.relay(), spec.tools(), spec.timeoutMillis()));
                command.addAll(List.of("run", "--format", "json", "--agent", "pull-worker"));
                if (spec.model() != null) {
                    command.addAll(List.of("-m", spec.model()));
                }
                command.addAll(List.of("--dir", spec.workerDir().toString(), spec.prompt()));
            }
            case CODEX -> {
                command.addAll(List.of("exec", "--json", "--skip-git-repo-check", "--sandbox", "read-only", "-C",
                        spec.workerDir().toString(), "-c", "approval_policy=\"never\""));
                command.addAll(McpConfigWriter.codex(spec.relay(), spec.tools(),
                        spec.timeoutMillis() == null ? null : spec.timeoutMillis() / 1000));
                if (spec.jsonSchema() != null) {
                    var schema = spec.workerDir().resolve("output-schema.json");
                    Files.writeString(schema, spec.jsonSchema());
                    command.addAll(List.of("--output-schema", schema.toString()));
                }
                if (spec.model() != null) {
                    command.addAll(List.of("-m", spec.model()));
                }
                command.add(spec.prompt());
            }
        }
        return List.copyOf(command);
    }

    /**
     * @param line one line of the harness's JSON output
     * @return whether it reports the end (result or failure) of an MCP tool call
     */
    boolean reportsToolResult(String line) {
        return switch (this) {
            case CLAUDE -> line.contains("\"tool_result\"");
            case OPENCODE -> line.contains("\"type\":\"tool_use\"");
            case CODEX -> line.contains("\"item.completed\"") && line.contains("mcp_tool_call");
        };
    }

    /**
     * The environment of a worker: the driver's, without the variables of its own host session, plus what the
     * harness needs for the configured MCP call timeout.
     *
     * @param base          the driver's environment
     * @param timeoutMillis the MCP call timeout, {@code null} for the harness default
     * @return the environment
     */
    Map<String, String> environment(Map<String, String> base, Long timeoutMillis) {
        var env = new LinkedHashMap<String, String>();
        base.forEach((name, value) -> {
            if (HOST_SESSION_PREFIXES.stream().noneMatch(name::startsWith)) {
                env.put(name, value);
            }
        });
        if (this == CLAUDE && timeoutMillis != null) {
            env.put("MCP_TOOL_TIMEOUT", Long.toString(timeoutMillis));
        }
        return env;
    }
}
