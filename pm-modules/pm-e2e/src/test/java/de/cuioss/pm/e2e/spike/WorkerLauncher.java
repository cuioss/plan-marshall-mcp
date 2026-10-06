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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Starts worker generations: mints the job token, writes the per-worker MCP configuration whose only server is
 * {@code <PM_MCP_HOME>/bin/pm-mcp serve --job --socket <socket>}, and starts the harness through {@code pm-exec}. On
 * Linux {@code pm-exec} confines it with Landlock: no read of {@code <PM_MCP_BASE>}, writes only to the worker
 * directory, the harness's own state paths, {@code /tmp}, {@code /dev} and {@code -Dspike.write-extra}. macOS jobs
 * run unconfined.
 */
final class WorkerLauncher {

    /** The stub tools of the task protocol with explicit acknowledgement. */
    static final List<String> PROTOCOL_TOOLS = List.of("pull_wait", "pull_submit", "pull_ack");

    private final SpikeSettings settings;
    private final SpikeStage stage;
    private final SpikeDaemon daemon;
    private final Path runDir;
    private final Path workspace;
    private final Path harnessExecutable;

    /**
     * @param settings  the flags
     * @param stage     the staged installation
     * @param daemon    the running daemon
     * @param runDir    where the harness output logs go
     * @param workspace the parent of the worker directories, outside every repository
     * @throws IOException if the harness is not installed
     */
    WorkerLauncher(SpikeSettings settings, SpikeStage stage, SpikeDaemon daemon, Path runDir, Path workspace)
            throws IOException {
        this.settings = settings;
        this.stage = stage;
        this.daemon = daemon;
        this.runDir = runDir;
        this.workspace = workspace;
        this.harnessExecutable = settings.harness().locate()
                .orElseThrow(() -> new IOException(settings.harness().id() + " is not on PATH"));
    }

    /** @return whether workers are confined (Linux) */
    static boolean confined() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    }

    /** @return the user's home */
    static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    /**
     * @param workerDir the worker directory
     * @return the paths the worker may write, in the order given to {@code pm-exec}
     */
    List<Path> writeSet(Path workerDir) {
        var paths = new LinkedHashSet<Path>();
        paths.add(workerDir);
        paths.addAll(settings.harness().stateDirs(home()));
        paths.add(Path.of("/tmp"));
        paths.add(Path.of("/dev"));
        paths.addAll(settings.extraWrites());
        return List.copyOf(paths);
    }

    /**
     * Mints a job and starts one worker generation.
     *
     * @param worker      the worker
     * @param generation  the generation
     * @param promptKind  a label of the prompt for the logs
     * @param prompt      the prompt
     * @param tools       the stub tools it may call
     * @param jsonSchema  a schema of its final answer, may be {@code null}
     * @param timeoutMs   the MCP call timeout to configure, {@code null} for the harness default
     * @return the running worker
     * @throws IOException if the job cannot be minted or the process not started
     */
    Worker launch(String worker, int generation, String promptKind, String prompt, List<String> tools,
            String jsonSchema, Long timeoutMs) throws IOException {
        var job = daemon.mint(worker, generation, "worker");
        var workerDir = Files.createDirectories(workspace.resolve(worker + "-g" + generation));
        var relay = List.of(stage.relay().toString(), "serve", "--job", "--socket", daemon.socket().toString());
        var spec = new Harness.WorkerSpec(workerDir, prompt, settings.model(), relay, tools, jsonSchema, timeoutMs);
        var harnessCommand = settings.harness().command(harnessExecutable, spec);
        var command = new ArrayList<String>();
        command.add(stage.exec().toString());
        if (confined()) {
            command.add("--deny-read");
            command.add(daemon.base().toString());
            for (var path : writeSet(workerDir)) {
                if (Files.exists(path)) {
                    command.add("--write");
                    command.add(path.toString());
                }
            }
        }
        command.add("--");
        command.addAll(harnessCommand);
        var builder = new ProcessBuilder(command).directory(workerDir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        var environment = builder.environment();
        var base = new HashMap<>(environment);
        environment.clear();
        environment.putAll(settings.harness().environment(base, timeoutMs));
        environment.put("PM_MCP_HOME", stage.home().toString());
        environment.put("PM_MCP_JOB_TOKEN", job.token());
        environment.put("PM_MCP_JOB_ID", job.jobId());
        var process = builder.start();
        var logFile = runDir.resolve("harness-" + worker + "g" + generation + ".jsonl");
        return new Worker(worker, generation, "worker", promptKind, job, process, workerDir, logFile);
    }

    /** @return the harness */
    Harness harness() {
        return settings.harness();
    }

    /** @return the harness executable */
    Path harnessExecutable() {
        return harnessExecutable;
    }
}
