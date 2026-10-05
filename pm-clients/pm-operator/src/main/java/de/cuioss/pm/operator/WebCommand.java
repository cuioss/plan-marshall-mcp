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
import java.util.LinkedHashMap;
import java.util.Map;

import de.cuioss.pm.api.http.HttpRequest;
import de.cuioss.pm.api.json.JsonTree;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code pm-operator web enable|disable}: opens or closes the runtime's web listener while it runs
 * ({@code PUT /api/v1/web}); starts the runtime on demand.
 */
@Command(name = "web", description = "Enables or disables the runtime's web access.")
final class WebCommand {

    /** The default port of the web listener ({@code machine-config.json}, {@code web.port}). */
    static final int DEFAULT_PORT = 7420;

    private final OperatorContext context;

    @Spec
    CommandSpec spec;

    WebCommand(OperatorContext context) {
        this.context = context;
    }

    @Command(name = "enable", description = "Opens the web listener (loopback, or all interfaces with --lan).")
    int enable(@Option(names = "--lan", description = "HTTPS on all interfaces.") boolean lan,
            @Option(names = "--port", paramLabel = "N", defaultValue = "" + DEFAULT_PORT,
                    description = "TCP port, default ${DEFAULT-VALUE}.") int port)
            throws IOException {
        return put(true, lan, port);
    }

    @Command(name = "disable", description = "Closes the web listener.")
    int disable(@Option(names = "--port", paramLabel = "N", defaultValue = "" + DEFAULT_PORT,
            description = "TCP port, default ${DEFAULT-VALUE}.") int port) throws IOException {
        return put(false, false, port);
    }

    private int put(boolean enabled, boolean lan, int port) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("enabled", enabled);
        body.put("lan", lan);
        body.put("port", port);
        var response = context.runtime(true).send(HttpRequest.json("PUT", "/api/v1/web", JsonTree.write(body)));
        if (response.status() != 200) {
            throw new OperatorRefusedException("web", response);
        }
        var out = spec.commandLine().getOut();
        out.println("web       " + describe(JsonTree.asObject(JsonTree.parse(response.body()))));
        out.flush();
        return 0;
    }

    /**
     * @param web the {@code web} object of the status, may be {@code null}
     * @return a readable line
     */
    static String describe(Map<String, Object> web) {
        if (web == null) {
            return "-";
        }
        if (!Boolean.TRUE.equals(web.get("enabled"))) {
            return "disabled";
        }
        return "enabled (" + (Boolean.TRUE.equals(web.get("lan")) ? "lan" : "loopback") + "), port "
                + web.get("port") + ", " + (Boolean.TRUE.equals(web.get("open")) ? "open" : "not open");
    }
}
