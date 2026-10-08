/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal HTTP/1.1 client over a JDK {@link SocketChannel} on a {@link UnixDomainSocketAddress}
 * ({@code java.net.http.HttpClient} cannot use Unix domain sockets). Each exchange uses its own
 * connection ({@code Connection: close}); bodies are framed by {@code Content-Length},
 * {@code chunked}, or the end of the connection.
 */
public final class UnixSocketHttpClient {

    private static final int BUFFER_SIZE = 8192;

    private final Path socket;

    /**
     * @param socket the path of the runtime's socket
     */
    public UnixSocketHttpClient(Path socket) {
        this.socket = Objects.requireNonNull(socket, "socket");
    }

    /** @return the socket path */
    public Path socket() {
        return socket;
    }

    /**
     * Connects, sends the request, and reads the response head.
     *
     * @param request the request
     * @return the open exchange; the caller closes it
     * @throws RuntimeUnreachableException when no process accepts the connection (nothing was sent)
     * @throws IOException                 on any later failure
     */
    @SuppressWarnings("resource") // the channel is owned by the returned exchange
    public HttpExchange open(HttpRequest request) throws IOException {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
        } catch (IOException e) {
            if (channel != null) {
                channel.close();
            }
            throw new RuntimeUnreachableException(socket, e);
        }
        try {
            var out = Channels.newOutputStream(channel);
            out.write(encode(request));
            out.flush();
            var in = new BufferedInputStream(Channels.newInputStream(channel), BUFFER_SIZE);
            return readResponse(channel, request, in);
        } catch (IOException e) {
            channel.close();
            throw e;
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

    static byte[] encode(HttpRequest request) {
        var head = new StringBuilder(256)
                .append(request.method()).append(' ').append(request.target()).append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\n")
                .append("Connection: close\r\n");
        request.headers().forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        if (request.bodyLength() > 0 || !"GET".equals(request.method()) && !"DELETE".equals(request.method())) {
            head.append("Content-Length: ").append(request.bodyLength()).append("\r\n");
        }
        head.append("\r\n");
        var bytes = new ByteArrayOutputStream(head.length() + request.bodyLength());
        bytes.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
        bytes.writeBytes(request.bodyUnsafe());
        return bytes.toByteArray();
    }

    static HttpExchange readResponse(Closeable connection, HttpRequest request, InputStream in)
            throws IOException {
        while (true) {
            var statusLine = HttpLines.requireLine(in);
            var status = parseStatus(statusLine);
            var headers = readHeaders(in);
            if (status >= 100 && status < 200) {
                continue;
            }
            return new HttpExchange(connection, status, headers, body(request, status, headers, in));
        }
    }

    private static int parseStatus(String statusLine) throws HttpProtocolException {
        var parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
            throw new HttpProtocolException("Invalid status line: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new HttpProtocolException("Invalid status line: " + statusLine);
        }
    }

    private static Map<String, List<String>> readHeaders(InputStream in) throws IOException {
        var headers = new LinkedHashMap<String, List<String>>();
        String line;
        var count = 0;
        while (!(line = HttpLines.requireLine(in)).isEmpty()) {
            if (++count > 256) {
                throw new HttpProtocolException("Too many response headers");
            }
            var colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpProtocolException("Invalid header line: " + line);
            }
            headers.computeIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), _ -> new ArrayList<>())
                    .add(line.substring(colon + 1).trim());
        }
        return headers;
    }

    private static InputStream body(HttpRequest request, int status, Map<String, List<String>> headers,
            InputStream in) throws HttpProtocolException {
        if ("HEAD".equals(request.method()) || status == 204 || status == 304) {
            return BodyInputStreams.empty();
        }
        var transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.getLast().toLowerCase(Locale.ROOT).endsWith("chunked")) {
            return BodyInputStreams.chunked(in);
        }
        var contentLength = headers.get("content-length");
        if (contentLength != null) {
            try {
                var length = Long.parseLong(contentLength.getFirst());
                if (length < 0) {
                    throw new HttpProtocolException("Negative Content-Length");
                }
                return BodyInputStreams.bounded(in, length);
            } catch (NumberFormatException e) {
                throw new HttpProtocolException("Invalid Content-Length: " + contentLength.getFirst());
            }
        }
        return in;
    }
}
