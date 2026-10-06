/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike;

import java.util.Optional;


import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Experiment of M8 (Milestone 0, Part B): answers the MCP {@code 2026-07-28} method {@code subscriptions/listen}
 * itself instead of quarkus-mcp-server, to observe what a host does when the sessionless runtime does not hold a
 * standing notification stream. Active only with {@code pm.spike.listen}:
 * <ul>
 * <li>{@code refuse}: JSON-RPC error {@code -32601} (method not found);</li>
 * <li>{@code ignore}: an empty result, the stream ends at once without {@code notifications/subscriptions/acknowledged}.</li>
 * </ul>
 * Recognised by the relay's {@code Mcp-Method} header.
 */
@ApplicationScoped
public class SpikeListenFilter {

    static final String LISTEN = "subscriptions/listen";
    static final String REFUSE = "refuse";
    static final String IGNORE = "ignore";
    private static final int PRIORITY = 90;

    private final String mode;
    private final SpikeTrafficRecord traffic;

    /**
     * @param mode    {@code refuse} or {@code ignore}; the filter stays inactive without it
     * @param traffic the traffic record, which also notes the answers of this filter
     */
    SpikeListenFilter(@ConfigProperty(name = "pm.spike.listen") Optional<String> mode, SpikeTrafficRecord traffic) {
        this.traffic = traffic;
        this.mode = mode.filter(value -> REFUSE.equals(value) || IGNORE.equals(value)).orElse(null);
    }

    void register(@Observes Filters filters) {
        if (mode == null) {
            return;
        }
        filters.register(context -> {
            if (!LISTEN.equals(context.request().getHeader("Mcp-Method")) || !"/mcp".equals(context.normalizedPath())) {
                context.next();
                return;
            }
            // Quarkus pauses the request until a route reads it; the filter reads the body itself
            context.request().resume();
            context.request().body().onSuccess(buffer -> {
                JsonObject request;
                try {
                    request = new JsonObject(buffer);
                } catch (DecodeException _) {
                    request = new JsonObject();
                }
                var answer = answer(mode, request.getValue("id"));
                traffic.record("rx", request, "spike-listen");
                traffic.record("tx", answer, "spike-listen");
                context.response().putHeader("Content-Type", "application/json").end(answer.encode());
            }).onFailure(context::fail);
        }, PRIORITY);
    }

    /**
     * @param mode {@code refuse} or {@code ignore}
     * @param id   the JSON-RPC id of the request
     * @return the JSON-RPC response
     */
    static JsonObject answer(String mode, Object id) {
        var response = new JsonObject().put("jsonrpc", "2.0").put("id", id);
        if (REFUSE.equals(mode)) {
            return response.put("error", new JsonObject().put("code", -32601).put("message", "Method not found: "
                    + LISTEN));
        }
        return response.put("result", new JsonObject());
    }
}
