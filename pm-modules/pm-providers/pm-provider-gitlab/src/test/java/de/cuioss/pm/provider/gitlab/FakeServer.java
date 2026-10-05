/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.gitlab;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** A JDK {@link HttpServer} fake: canned responses by method and path, recorded requests. */
final class FakeServer implements AutoCloseable {

    /** A recorded request. */
    record Request(String method, String pathAndQuery, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    /** A canned response. */
    record Response(int status, String body, Map<String, String> headers) {

        static Response json(int status, String body) {
            return new Response(status, body, Map.of("Content-Type", "application/json"));
        }
    }

    private final HttpServer server;
    private final Map<String, Deque<Response>> responses = new ConcurrentHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    FakeServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            requests.add(new Request(exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body));
            Response response = next(exchange.getRequestMethod() + " " + path);
            Headers headers = exchange.getResponseHeaders();
            response.headers().forEach(headers::add);
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    /** Queues a response; the last queued response of a key repeats. */
    FakeServer on(String method, String path, Response response) {
        responses.computeIfAbsent(method + " " + path, k -> new ConcurrentLinkedDeque<>()).add(response);
        return this;
    }

    private Response next(String key) {
        Deque<Response> queue = responses.get(key);
        if (queue == null || queue.isEmpty()) {
            return Response.json(404, "{\"message\":\"Not Found\"}");
        }
        return queue.size() > 1 ? queue.poll() : queue.peek();
    }

    /** Base URI with the given host name (127.0.0.1 or localhost give two origins on one machine). */
    URI base(String host) {
        return URI.create("http://" + host + ":" + server.getAddress().getPort() + "/");
    }

    URI base() {
        return base("127.0.0.1");
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    private boolean closed;

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            server.stop(0);
        }
    }
}
