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

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;


import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
import io.quarkus.vertx.http.runtime.filters.AbstractResponseWrapper;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.RoutingContextDecorator;
import io.vertx.ext.web.impl.RoutingContextInternal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Keeps hosts from holding a standing notification stream on the sessionless runtime
 * (doc/specification/runtime-model/03-relay.adoc, Host Protocol Forms).
 * <p>
 * The runtime serves every request on a transient connection and sends no list-change notifications, while
 * quarkus-mcp-server 2.0.2 announces {@code listChanged: true} for programmatically managed tools and accepts the
 * MCP {@code 2026-07-28} method {@code subscriptions/listen} with a stream it never feeds. For a request in the
 * relay's form, recognised by its {@code Mcp-Method} header, this filter therefore
 * <ul>
 * <li>answers {@code subscriptions/listen} itself with the JSON-RPC error {@code -32601} (method not found), and</li>
 * <li>rewrites the {@code capabilities} of the {@code initialize} and {@code server/discover} results so that
 * {@code listChanged} of {@code tools}, {@code resources} and {@code prompts} is {@code false} wherever the server
 * declares it.</li>
 * </ul>
 * The filter runs after authentication, so an unauthenticated request is refused with {@code 401} before it.
 *
 * @since 0.1
 */
@ApplicationScoped
public class NotificationStreamFilter {

    static final String METHOD_HEADER = "Mcp-Method";
    static final String LISTEN = "subscriptions/listen";
    static final Set<String> CAPABILITY_METHODS = Set.of("initialize", "server/discover");
    static final List<String> LIST_CHANGED_CAPABILITIES = List.of("tools", "resources", "prompts");
    static final int METHOD_NOT_FOUND = -32601;
    /** Below the authentication (200) and authorization (100) filters, so it sees authenticated requests only. */
    static final int PRIORITY = 90;
    /** The routing-context key under which the MCP endpoint hands the server name to the message handler. */
    static final String SERVER_NAME_KEY = "mcp.http.server-name";
    private static final String EVENT_PREFIX = "event: message\ndata: ";
    private static final String EVENT_SUFFIX = "\n\n";

    private final StreamableHttpMcpMessageHandler handler;

    /**
     * @param handler the Streamable HTTP message handler of the MCP server
     */
    public NotificationStreamFilter(StreamableHttpMcpMessageHandler handler) {
        this.handler = handler;
    }

    void register(@Observes Filters filters) {
        filters.register(this::filter, PRIORITY);
    }

    private void filter(RoutingContext context) {
        var method = context.request().getHeader(METHOD_HEADER);
        if (method == null || context.request().method() != HttpMethod.POST || !"/mcp".equals(context.normalizedPath())
                || !(context instanceof RoutingContextInternal internal)) {
            context.next();
        } else if (LISTEN.equals(method)) {
            withBody(context, body -> context.response().putHeader("Content-Type", "application/json")
                    .end(refusal(idOf(body)).encode()));
        } else if (CAPABILITY_METHODS.contains(method)) {
            withBody(context, body -> {
                internal.setBody(body);
                context.put(SERVER_NAME_KEY, McpServer.DEFAULT);
                handler.handle(new CapabilityRewriting(context.currentRoute(), internal));
            });
        } else {
            context.next();
        }
    }

    /** Reads the request body, which Quarkus keeps paused until a route reads it. */
    private static void withBody(RoutingContext context, Consumer<Buffer> action) {
        context.request().resume();
        context.request().body().onSuccess(action::accept).onFailure(context::fail);
    }

    private static Object idOf(Buffer body) {
        try {
            return new JsonObject(body).getValue("id");
        } catch (DecodeException | ClassCastException _) {
            return null;
        }
    }

    /**
     * @param id the JSON-RPC id of the request
     * @return the JSON-RPC error answering {@code subscriptions/listen}
     */
    static JsonObject refusal(Object id) {
        return new JsonObject().put("jsonrpc", "2.0").put("id", id).put("error",
                new JsonObject().put("code", METHOD_NOT_FOUND).put("message", "Method not found: " + LISTEN));
    }

    /**
     * Sets {@code listChanged} to {@code false} in the {@code capabilities} of a JSON-RPC result, where present.
     *
     * @param message the JSON-RPC message, changed in place
     * @return {@code message}
     */
    static JsonObject withoutListChanged(JsonObject message) {
        if (message.getValue("result") instanceof JsonObject result
                && result.getValue("capabilities") instanceof JsonObject capabilities) {
            for (String name : LIST_CHANGED_CAPABILITIES) {
                if (capabilities.getValue(name) instanceof JsonObject capability && capability.containsKey("listChanged")) {
                    capability.put("listChanged", false);
                }
            }
        }
        return message;
    }

    /**
     * Rewrites one JSON-RPC message encoded as a whole response body.
     *
     * @param body the body
     * @return the rewritten body, or {@code body} if it is no JSON object
     */
    static Buffer rewriteBody(Buffer body) {
        try {
            return withoutListChanged(new JsonObject(body)).toBuffer();
        } catch (DecodeException | ClassCastException _) {
            return body;
        }
    }

    /**
     * Rewrites one SSE {@code message} event carrying a JSON-RPC message.
     *
     * @param event the event text
     * @return the rewritten event, or {@code event} if it is no message event with a JSON object
     */
    static String rewriteEvent(String event) {
        if (!event.startsWith(EVENT_PREFIX) || !event.endsWith(EVENT_SUFFIX)) {
            return event;
        }
        try {
            var message = new JsonObject(event.substring(EVENT_PREFIX.length(), event.length() - EVENT_SUFFIX.length()));
            return EVENT_PREFIX + withoutListChanged(message).encode() + EVENT_SUFFIX;
        } catch (DecodeException | ClassCastException _) {
            return event;
        }
    }

    /** The routing context the message handler sees: the request's own, with a rewriting response. */
    private static final class CapabilityRewriting extends RoutingContextDecorator {

        private final HttpServerResponse response;

        CapabilityRewriting(Route route, RoutingContextInternal context) {
            super(route, context);
            this.response = new RewritingResponse(context.response());
        }

        @Override
        public HttpServerResponse response() {
            return response;
        }
    }

    /** Rewrites the JSON body or the SSE events of the response; everything else passes through. */
    private static final class RewritingResponse extends AbstractResponseWrapper {

        RewritingResponse(HttpServerResponse delegate) {
            super(delegate);
        }

        @Override
        public Future<Void> end(Buffer chunk) {
            return super.end(rewriteBody(chunk));
        }

        @Override
        public Future<Void> write(String chunk) {
            return super.write(rewriteEvent(chunk));
        }
    }
}
