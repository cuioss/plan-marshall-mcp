/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.tools;

import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Keeps the runtime sessionless for the {@code initialize} of a session-opening host
 * (doc/specification/runtime-model/03-relay.adoc, Host Protocol Forms).
 * <p>
 * quarkus-mcp-server 2.0.2 answers {@code initialize} (protocol {@code 2025-11-25}) by registering a connection
 * and issuing {@code Mcp-Session-Id}; only {@code server/discover} and requests with {@code _meta} protocol
 * version {@code 2026-07-28} are served on transient connections. For a request in the relay's form, which
 * carries the {@code Mcp-Method} header on every message, this filter removes the session id from the response
 * and drops the registered connection. Every later request of that host arrives without a session id and is
 * served on a per-request connection ({@code quarkus.mcp.server.http.streamable.auto-init=true}).
 *
 * @since 0.1
 */
@ApplicationScoped
public class SessionlessInitialize {

    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String METHOD_HEADER = "Mcp-Method";
    static final String INITIALIZE = "initialize";
    static final int PRIORITY = 100;

    private final ConnectionManager connections;

    /**
     * @param connections the connection registry of the MCP server
     */
    public SessionlessInitialize(ConnectionManager connections) {
        this.connections = connections;
    }

    void register(@Observes Filters filters) {
        filters.register(context -> {
            if (INITIALIZE.equals(context.request().getHeader(METHOD_HEADER))
                    && "/mcp".equals(context.normalizedPath())) {
                var response = context.response();
                response.headersEndHandler(_ -> {
                    var session = response.headers().get(SESSION_HEADER);
                    if (session != null) {
                        response.headers().remove(SESSION_HEADER);
                        connections.remove(session);
                    }
                });
            }
            context.next();
        }, PRIORITY);
    }
}
