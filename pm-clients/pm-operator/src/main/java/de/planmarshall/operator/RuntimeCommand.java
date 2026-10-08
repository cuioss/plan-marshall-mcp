/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.operator;

import java.io.IOException;
import java.util.Map;

import de.planmarshall.api.http.HttpRequest;
import de.planmarshall.api.runtime.RuntimeUnavailableException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code pm-operator runtime start|stop}: the on-demand start, reported as running only after the
 * authenticated round trip, and the stop request ({@code POST /api/v1/runtime/stop}), which never
 * starts a runtime.
 */
@Command(name = "runtime", description = "Starts or stops the runtime.")
final class RuntimeCommand {

    private final OperatorContext context;

    @Spec
    CommandSpec spec;

    RuntimeCommand(OperatorContext context) {
        this.context = context;
    }

    @Command(name = "start", description = "Starts the runtime unless one runs.")
    int start() throws IOException {
        var status = StatusCommand.fetch(context.runtime(true));
        print("runtime   running (pid " + status.get("pid") + ")");
        return 0;
    }

    @Command(name = "stop", description = "Asks the runtime to stop.")
    int stop() throws IOException {
        try {
            var response = context.runtime(false).send(new HttpRequest("POST", "/api/v1/runtime/stop",
                    Map.of(), null));
            if (response.status() != 202) {
                throw new OperatorRefusedException("runtime stop", response);
            }
            print("runtime   stopping");
        } catch (RuntimeUnavailableException e) {
            print("runtime   not running");
        }
        return 0;
    }

    private void print(String line) {
        var out = spec.commandLine().getOut();
        out.println(line);
        out.flush();
    }
}
