/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.provider.ci;

import de.cuioss.http.client.handler.HttpHandler;
import de.cuioss.http.client.handler.RedirectNotAllowedException;
import de.cuioss.http.client.handler.RedirectPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.planmarshall.provider.ci.CiResponse.Outcome;

/**
 * The shared HTTP base of the native provider clients, over {@code cui-http}'s {@link HttpHandler}.
 * <ul>
 *   <li><b>Origin-pinned authentication</b>: a request is sent only to the endpoint's origin and
 *       carries {@code Authorization: Bearer <token>}; a target outside the origin is refused before
 *       anything is sent. Redirects are followed by {@code cui-http}, same-origin or to an allowlisted
 *       redirect host, and {@code cui-http} strips {@code Authorization} on every cross-origin hop
 *       and refuses an {@code https} to {@code http} downgrade.</li>
 *   <li><b>Pagination</b>: {@link #getAll(String, int)} follows {@code Link: <…>; rel="next"} and
 *       reports whether the read is complete.</li>
 *   <li><b>Conditional requests</b>: an {@code ETag} is sent as {@code If-None-Match};
 *       {@code 304} is {@link Outcome#NOT_MODIFIED}.</li>
 *   <li><b>Rate limits</b>: {@code 429}, and {@code 403} with an exhausted
 *       {@code x-ratelimit-remaining} or a {@code Retry-After}, are {@link Outcome#RATE_LIMITED} with
 *       the reset instant from {@code Retry-After}, {@code x-ratelimit-reset} or
 *       {@code ratelimit-reset}.</li>
 * </ul>
 * Every request carries the connect timeout ({@value #CONNECT_TIMEOUT_SECONDS} s) and the read timeout
 * ({@value #READ_TIMEOUT_SECONDS} s) of the outbound HTTPS calls.
 *
 * @since 0.1
 */
public final class CiHttpClient implements AutoCloseable {

    /** Connect timeout of every request, in seconds. */
    public static final int CONNECT_TIMEOUT_SECONDS = 10;
    /** Read timeout of every request, in seconds. */
    public static final int READ_TIMEOUT_SECONDS = 60;
    /** Wait applied to a rate limit that names no reset instant. */
    static final Duration DEFAULT_RATE_LIMIT_WAIT = Duration.ofSeconds(60);

    private static final Pattern LINK_NEXT = Pattern.compile("<([^>]+)>\\s*;[^,]*rel=\"?next\"?");
    private static final String AUTHORIZATION = "Authorization";

    private final CiEndpoint endpoint;
    private final TokenSource tokens;
    private final Map<String, String> defaultHeaders;
    private final Clock clock;
    private final HttpHandler handler;

    /**
     * @param endpoint       the provider endpoint
     * @param tokens         the token source, resolved per request
     * @param defaultHeaders headers sent with every request (e.g. {@code Accept})
     */
    public CiHttpClient(CiEndpoint endpoint, TokenSource tokens, Map<String, String> defaultHeaders) {
        this(endpoint, tokens, defaultHeaders, Clock.systemUTC());
    }

    CiHttpClient(CiEndpoint endpoint, TokenSource tokens, Map<String, String> defaultHeaders, Clock clock) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.defaultHeaders = Map.copyOf(defaultHeaders);
        this.clock = clock;
        this.handler = HttpHandler.builder()
                .uri(endpoint.baseUri())
                .connectionTimeoutSeconds(CONNECT_TIMEOUT_SECONDS)
                .readTimeoutSeconds(READ_TIMEOUT_SECONDS)
                .allowInsecureHttp(endpoint.cleartextLoopback())
                .redirectPolicy(RedirectPolicy.builder().allowedHosts(endpoint.redirectHosts()).build())
                .build();
    }

    /**
     * @return the endpoint
     */
    public CiEndpoint endpoint() {
        return endpoint;
    }

    /**
     * @param pathOrUri a path relative to the base, or an absolute URI on the origin
     * @return the response
     */
    public CiResponse get(String pathOrUri) {
        return send("GET", pathOrUri, Optional.empty(), Optional.empty());
    }

    /**
     * Conditional GET.
     *
     * @param pathOrUri a path relative to the base, or an absolute URI on the origin
     * @param etag      the {@code ETag} of the cached representation
     * @return the response; {@link Outcome#NOT_MODIFIED} when the representation is unchanged
     */
    public CiResponse get(String pathOrUri, String etag) {
        return send("GET", pathOrUri, Optional.empty(), Optional.of(etag));
    }

    /**
     * Sends a request with an optional JSON body.
     *
     * @param method    the HTTP method
     * @param pathOrUri a path relative to the base, or an absolute URI on the origin
     * @param jsonBody  the JSON body (in memory, never a file)
     * @param etag      the {@code ETag} for {@code If-None-Match}
     * @return the response
     */
    public CiResponse send(String method, String pathOrUri, Optional<String> jsonBody, Optional<String> etag) {
        URI uri = endpoint.resolve(pathOrUri);
        if (!endpoint.sameOrigin(uri)) {
            return CiResponse.failure(Outcome.REFUSED, "outside the origin of " + endpoint.baseUri());
        }
        HttpRequest.Builder builder = handler.requestBuilder().uri(uri)
                .method(method, jsonBody.map(BodyPublishers::ofString).orElse(BodyPublishers.noBody()));
        defaultHeaders.forEach(builder::header);
        jsonBody.ifPresent(b -> builder.header("Content-Type", "application/json"));
        etag.ifPresent(e -> builder.header("If-None-Match", e));
        tokens.token().ifPresent(t -> builder.header(AUTHORIZATION, "Bearer " + t));
        try {
            return toResponse(handler.send(builder.build(), BodyHandlers.ofString()));
        } catch (RedirectNotAllowedException e) {
            return CiResponse.failure(Outcome.REFUSED, "redirect refused: " + e.getReason());
        } catch (IOException e) {
            return CiResponse.failure(Outcome.TRANSPORT_FAILED, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CiResponse.failure(Outcome.TRANSPORT_FAILED, "interrupted");
        }
    }

    /**
     * Reads every page of a paginated collection.
     *
     * @param path     the first page
     * @param maxPages the page cap
     * @return the pages and whether the read is complete
     */
    public PagedResult getAll(String path, int maxPages) {
        var pages = new ArrayList<String>();
        CiResponse response = get(path);
        while (response.isOk()) {
            pages.add(response.body());
            Optional<URI> next = response.next();
            if (next.isEmpty()) {
                return new PagedResult(pages, true, response);
            }
            if (pages.size() >= maxPages || !endpoint.sameOrigin(next.get())) {
                return new PagedResult(pages, false, response);
            }
            response = get(next.get().toString());
        }
        return new PagedResult(pages, false, response);
    }

    private CiResponse toResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        HttpHeaders headers = response.headers();
        Optional<String> etag = headers.firstValue("ETag");
        Optional<URI> next = headers.allValues("Link").stream().map(CiHttpClient::nextLink).flatMap(Optional::stream)
                .findFirst();
        if (status == 304) {
            return new CiResponse(Outcome.NOT_MODIFIED, status, "", etag, Optional.empty(), Optional.empty(), "");
        }
        if (status >= 200 && status < 300) {
            return new CiResponse(Outcome.OK, status, response.body(), etag, next, Optional.empty(), "");
        }
        if (isRateLimited(status, headers)) {
            return new CiResponse(Outcome.RATE_LIMITED, status, response.body(), etag, Optional.empty(),
                    Optional.of(resetAt(headers)), "rate limited");
        }
        return new CiResponse(Outcome.HTTP_ERROR, status, response.body(), etag, Optional.empty(), Optional.empty(),
                "HTTP " + status);
    }

    private static boolean isRateLimited(int status, HttpHeaders headers) {
        if (status == 429) {
            return true;
        }
        return status == 403 && (headers.firstValue("x-ratelimit-remaining").filter("0"::equals).isPresent()
                || headers.firstValue("Retry-After").isPresent());
    }

    private Instant resetAt(HttpHeaders headers) {
        Instant now = clock.instant();
        Optional<Long> retryAfter = headers.firstValue("Retry-After").flatMap(CiHttpClient::seconds);
        if (retryAfter.isPresent()) {
            return now.plusSeconds(retryAfter.get());
        }
        return headers.firstValue("x-ratelimit-reset").or(() -> headers.firstValue("ratelimit-reset"))
                .flatMap(CiHttpClient::seconds).map(Instant::ofEpochSecond)
                .orElse(now.plus(DEFAULT_RATE_LIMIT_WAIT));
    }

    private static Optional<Long> seconds(String value) {
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static Optional<URI> nextLink(String header) {
        Matcher matcher = LINK_NEXT.matcher(header);
        return matcher.find() ? Optional.of(URI.create(matcher.group(1))) : Optional.empty();
    }

    @Override
    public void close() {
        handler.close();
    }
}
