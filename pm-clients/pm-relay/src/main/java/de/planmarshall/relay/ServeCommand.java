/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.relay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

import de.planmarshall.api.host.HostDetection;
import de.planmarshall.api.runtime.RuntimeAccess;
import de.planmarshall.api.runtime.RuntimeClient;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code pm-mcp serve}: the STDIO relay of a host session ({@code --client <client>}, runtime token,
 * on-demand start) or of a worker ({@code --job --socket <path>}, job token and generation from the
 * environment, no file of {@code <PM_MCP_BASE>} read, never a runtime start).
 */
@Command(name = "serve", description = "STDIO relay between a host or worker and the PM-MCP runtime.")
final class ServeCommand implements Callable<Integer> {

    /** Exit code of a refused start. */
    static final int EXIT_REFUSED = 2;
    /** Exit code of a runtime failure. */
    static final int EXIT_FAILURE = 1;

    static final String ENV_JOB_ID = "PM_MCP_JOB_ID";
    static final String ENV_JOB_TOKEN = "PM_MCP_JOB_TOKEN";

    private final CliContext context;

    @Spec
    CommandSpec spec;

    @Option(names = "--client", paramLabel = "<client>",
            description = "Client id of the host session: claude, opencode, antigravity, codex, or neutral.")
    String client;

    @Option(names = "--job", description = "Relay of a worker; job token and generation come from the environment.")
    boolean job;

    @Option(names = "--socket", paramLabel = "<path>", description = "Runtime socket of a worker relay.")
    Path socket;

    ServeCommand(CliContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        var err = spec.commandLine().getErr();
        var environment = context.environment();
        RuntimeClient runtime;
        RelayMode mode;
        if (job) {
            var token = environment.get(ENV_JOB_TOKEN);
            var generation = environment.get(ENV_JOB_ID);
            if (socket == null || client != null || isBlank(token) || isBlank(generation)) {
                return refuse("--job needs --socket <path>, no --client, and PM_MCP_JOB_TOKEN and PM_MCP_JOB_ID");
            }
            runtime = RuntimeAccess.job(socket, token, Map.of());
            mode = RelayMode.worker(generation);
        } else {
            if (environment.containsKey(ENV_JOB_ID)) {
                return refuse("PM_MCP_JOB_ID is set: inside a worker only `pm-mcp serve --job` may connect");
            }
            if (socket != null || client == null || !HostDetection.clientIds().contains(client)) {
                return refuse("--client must be one of " + HostDetection.clientIds());
            }
            try {
                runtime = RuntimeAccess.local(environment, context.userHome(), Map.of(), true);
                mode = RelayMode.session(client, Workspace.resolve(context.workingDirectory()).toString());
            } catch (IOException e) {
                err.println("pm-mcp serve: " + e.getMessage());
                err.flush();
                return EXIT_FAILURE;
            }
        }
        return new Relay(runtime, mode, context.stdin(), spec.commandLine().getOut(), err).run();
    }

    private int refuse(String reason) {
        var err = spec.commandLine().getErr();
        err.println("pm-mcp serve: " + reason);
        err.flush();
        return EXIT_REFUSED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
