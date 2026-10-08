/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.web;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.mcp.server.runtime.RuntimeContext;
import de.planmarshall.runtime.PmMcpLogMessages;
import de.planmarshall.runtime.web.WebListenerConflictException;
import de.planmarshall.runtime.web.WebState;
import de.planmarshall.runtime.web.WebTls;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.vertx.http.runtime.VertxHttpRecorder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The web listener: a second Vert.x HTTP server on TCP beside the Unix-socket listener, opened and closed while
 * the runtime runs (doc/specification/cli-and-security/03-web-server.adoc).
 * <p>
 * Loopback mode serves plain HTTP on {@code 127.0.0.1:<port>}; LAN mode serves HTTPS on all interfaces with
 * the runtime's self-signed certificate ({@link WebTls}). The listener knows its connections, so the
 * authentication mechanism tells a web request from a socket request by the connection it arrived on.
 * Closing the server ends its connections, browser streams included; the socket listener is untouched.
 *
 * @since 0.1
 */
@ApplicationScoped
public class WebListener {

    private static final CuiLogger LOGGER = new CuiLogger(WebListener.class);
    private static final long TIMEOUT_SECONDS = 10;
    private static final String LOOPBACK = "127.0.0.1";

    private final Vertx vertx;
    private final RuntimeContext context;
    private final Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();
    private HttpServer server;
    private WebState state = WebState.disabled();

    /**
     * @param vertx   the runtime's Vert.x instance
     * @param context the runtime identity, for the TLS directory
     */
    public WebListener(Vertx vertx, RuntimeContext context) {
        this.vertx = vertx;
        this.context = context;
    }

    /**
     * @param connection the connection of a request
     * @return {@code true} if the request arrived on the web listener
     */
    public boolean serves(HttpConnection connection) {
        return connection != null && connections.contains(connection);
    }

    /** @return the current setting and live state */
    public synchronized WebState state() {
        return state;
    }

    /**
     * Opens or closes the listener; returns once it is open or closed.
     *
     * @param enabled whether web access is to be enabled
     * @param lan     the exposure: all interfaces over TLS instead of loopback
     * @param port    the port
     * @return the new state
     * @throws WebListenerConflictException if the port is occupied or the listener is open with the other
     *                                      exposure
     */
    public synchronized WebState apply(boolean enabled, boolean lan, int port) throws WebListenerConflictException {
        if (!enabled) {
            close();
            state = new WebState(false, state.lan(), port, false);
            return state;
        }
        if (server != null) {
            if (state.lan() != lan || state.port() != port) {
                throw new WebListenerConflictException("web access is enabled with lan=%s on port %s; disable it first"
                        .formatted(state.lan(), state.port()), null);
            }
            return state;
        }
        open(lan, port);
        state = new WebState(true, lan, port, true);
        return state;
    }

    void onShutdown(@Observes ShutdownEvent event) {
        synchronized (this) {
            close();
        }
    }

    private void open(boolean lan, int port) throws WebListenerConflictException {
        var options = new HttpServerOptions().setPort(port).setHost(lan ? "0.0.0.0" : LOOPBACK);
        var hostNames = new HashSet<>(Set.of(LOOPBACK, "localhost"));
        if (lan) {
            hostNames.add("::1");
        }
        if (lan) {
            try {
                var tls = WebTls.loadOrCreate(context.paths().base().resolve("web").resolve("tls"));
                hostNames.addAll(tls.names());
                options.setSsl(true).setKeyCertOptions(new PemKeyCertOptions()
                        .setCertValue(Buffer.buffer(tls.certificatePem()))
                        .setKeyValue(Buffer.buffer(tls.privateKeyPem())));
            } catch (IOException | GeneralSecurityException e) {
                throw new WebListenerConflictException("TLS material unavailable: " + e.getMessage(), e);
            }
        }
        var scheme = lan ? "https://" : "http://";
        var hosts = new HashSet<String>();
        var origins = new HashSet<String>();
        for (var name : hostNames) {
            var host = (name.contains(":") ? "[" + name + "]" : name) + ":" + port;
            hosts.add(host);
            origins.add(scheme + host);
        }
        var created = vertx.createHttpServer(options)
                .connectionHandler(connection -> {
                    connections.add(connection);
                    connection.closeHandler(_ -> connections.remove(connection));
                })
                .requestHandler(new WebRequestHandler(hosts, origins, VertxHttpRecorder.getRootHandler()));
        try {
            await(created.listen());
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn(PmMcpLogMessages.WARN.WEB_LISTENER_FAILED, port, e.getMessage());
            throw new WebListenerConflictException("port %s cannot be bound".formatted(port), e);
        }
        server = created;
        LOGGER.info(PmMcpLogMessages.INFO.WEB_LISTENER_OPENED, options.getHost(), port, lan);
    }

    private void close() {
        if (server == null) {
            return;
        }
        try {
            await(server.close());
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn(PmMcpLogMessages.WARN.WEB_LISTENER_FAILED, state.port(), e.getMessage());
        }
        server = null;
        connections.clear();
        LOGGER.info(PmMcpLogMessages.INFO.WEB_LISTENER_CLOSED, state.port());
    }

    private static <T> T await(Future<T> future) throws ExecutionException, TimeoutException {
        try {
            return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionException(e);
        }
    }
}
