/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.operator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;

import de.cuioss.pm.api.http.HttpRequest;
import de.cuioss.pm.api.json.JsonTree;
import de.cuioss.pm.api.runtime.RuntimeClient;
import de.cuioss.pm.api.runtime.RuntimeRecord;
import de.cuioss.pm.api.runtime.RuntimeUnavailableException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code pm-operator status}: the runtime status from {@code GET /api/v1/status}. It never starts
 * the runtime and writes nothing: without a live runtime record it reports "not running" from
 * {@code state/runtime.json} alone, and {@code running} only after the authenticated round trip.
 */
@Command(name = "status", description = "Shows the runtime status; never starts the runtime.")
final class StatusCommand implements Callable<Integer> {

    private final OperatorContext context;

    @Spec
    CommandSpec spec;

    @Option(names = "--json", description = "Prints the status as JSON.")
    boolean json;

    StatusCommand(OperatorContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        var out = spec.commandLine().getOut();
        var record = RuntimeRecord.read(context.paths().runtimeRecord());
        if (record.isEmpty() || !record.get().processAlive()) {
            return notRunning();
        }
        Map<String, Object> status;
        try {
            status = fetch(context.runtime(false));
        } catch (RuntimeUnavailableException e) {
            return notRunning();
        }
        if (json) {
            out.println(JsonTree.write(status));
        } else {
            out.print(render(status));
        }
        out.flush();
        return 0;
    }

    static Map<String, Object> fetch(RuntimeClient runtime) throws IOException {
        var response = runtime.send(HttpRequest.get(RuntimeClient.STATUS_PATH));
        if (response.status() != 200) {
            throw new OperatorRefusedException("status", response);
        }
        var status = JsonTree.asObject(JsonTree.parse(response.body()));
        if (status == null) {
            throw new OperatorRefusedException("status", response);
        }
        return status;
    }

    private int notRunning() {
        var out = spec.commandLine().getOut();
        out.println(json ? "{\"running\":false}" : "runtime   not running");
        out.flush();
        return 0;
    }

    static String render(Map<String, Object> status) {
        var text = new StringBuilder();
        line(text, "runtime", "running");
        line(text, "version", status.get("version"));
        line(text, "pid", status.get("pid"));
        line(text, "listener", status.get("listener"));
        line(text, "socket", status.get("socket_path"));
        line(text, "started", status.get("started_at"));
        line(text, "web", WebCommand.describe(JsonTree.object(status, "web")));
        return text.toString();
    }

    private static void line(StringBuilder text, String label, Object value) {
        text.append(String.format("%-9s %s%n", label, value == null ? "-" : value));
    }
}
