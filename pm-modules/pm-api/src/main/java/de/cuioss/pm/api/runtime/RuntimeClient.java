/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.runtime;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import de.cuioss.pm.api.MachinePaths;
import de.cuioss.pm.api.http.HttpExchange;
import de.cuioss.pm.api.http.HttpRequest;
import de.cuioss.pm.api.http.HttpResponse;
import de.cuioss.pm.api.http.RuntimeUnreachableException;
import de.cuioss.pm.api.http.UnixSocketHttpClient;

/**
 * The authenticated client of the runtime's socket, shared by both client binaries: it adds the
 * credential to every request, re-reads it once and retries once on a {@code 401}, and, where it was
 * given an {@link RuntimeStarter}, starts the runtime on demand when none answers.
 */
public final class RuntimeClient {

    /** Path of the runtime status endpoint, the liveness round trip. */
    public static final String STATUS_PATH = "/api/v1/status";

    private final UnixSocketHttpClient http;
    private final Authenticator authenticator;
    private final RuntimeStarter starter;
    private final Map<String, String> fixedHeaders;

    /**
     * @param http          the socket client
     * @param authenticator the credential source
     * @param starter       the on-demand start, {@code null} for a client that never starts the
     *                      runtime
     * @param fixedHeaders  headers sent on every request
     */
    public RuntimeClient(UnixSocketHttpClient http, Authenticator authenticator, RuntimeStarter starter,
            Map<String, String> fixedHeaders) {
        this.http = Objects.requireNonNull(http, "http");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.starter = starter;
        this.fixedHeaders = Map.copyOf(fixedHeaders);
    }

    /**
     * The client of a local session or operator command: runtime token, on-demand start.
     *
     * @param paths        the machine paths
     * @param tokenFile    the runtime token file
     * @param starter      the on-demand start, {@code null} for a command that never starts it
     * @param fixedHeaders headers sent on every request
     * @return the client
     */
    public static RuntimeClient local(MachinePaths paths, RuntimeTokenFile tokenFile, RuntimeStarter starter,
            Map<String, String> fixedHeaders) {
        return new RuntimeClient(new UnixSocketHttpClient(paths.socket()), Authenticator.runtimeToken(tokenFile),
                starter, fixedHeaders);
    }

    /**
     * Sends a request and returns the open exchange after the response head.
     *
     * @param request the request
     * @return the exchange; the caller closes it
     * @throws RuntimeUnavailableException  if no runtime answers and none could be started
     * @throws UnauthorizedException        if the runtime answers {@code 401} after the retry
     * @throws InsecureRuntimeFileException if the runtime-token check refuses a file
     * @throws IOException                  on a transport failure after the request was sent
     */
    public HttpExchange open(HttpRequest request) throws IOException {
        var started = false;
        var renewed = false;
        while (true) {
            HttpExchange exchange;
            try {
                exchange = http.open(withHeaders(request, authenticator.credential()));
            } catch (NoSuchFileException | RuntimeUnreachableException e) {
                if (starter == null || started) {
                    throw new RuntimeUnavailableException("The PM-MCP runtime is not running (" + http.socket() + ")",
                            e);
                }
                starter.ensureRunning();
                authenticator.renew();
                started = true;
                continue;
            }
            if (exchange.status() == 401) {
                exchange.close();
                if (!renewed && authenticator.renew()) {
                    renewed = true;
                    continue;
                }
                throw new UnauthorizedException("The PM-MCP runtime refused the credential (401)");
            }
            return exchange;
        }
    }

    /**
     * Sends a request and reads the whole response.
     *
     * @param request the request
     * @return the response
     * @throws IOException as {@link #open(HttpRequest)}
     */
    public HttpResponse send(HttpRequest request) throws IOException {
        try (var exchange = open(request)) {
            return new HttpResponse(exchange.status(), exchange.header("Content-Type"), exchange.readBody());
        }
    }

    /**
     * The liveness round trip: an authenticated {@code GET /api/v1/status} answered {@code 200},
     * without starting anything.
     *
     * @return {@code true} if a runtime answered
     * @throws InsecureRuntimeFileException if the runtime-token check refuses a file
     */
    public boolean isLive() throws InsecureRuntimeFileException {
        try {
            var credential = authenticator.credential();
            try (var exchange = http.open(withHeaders(HttpRequest.get(STATUS_PATH), credential))) {
                if (exchange.status() == 401) {
                    authenticator.renew();
                }
                exchange.readBody();
                return exchange.status() == 200;
            }
        } catch (InsecureRuntimeFileException e) {
            throw e;
        } catch (IOException e) {
            // absent token, no listener, or a runtime still starting: not live
            return false;
        }
    }

    private HttpRequest withHeaders(HttpRequest request, Authenticator.Credential credential) {
        var headers = new LinkedHashMap<>(fixedHeaders);
        headers.putAll(request.headers());
        headers.put(credential.header(), credential.value());
        return new HttpRequest(request.method(), request.target(), headers, request.body());
    }
}
