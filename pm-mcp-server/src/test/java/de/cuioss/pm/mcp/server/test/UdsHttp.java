/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal HTTP/1.1 client over a Unix domain socket for the tests: one request per connection
 * ({@code Connection: close}), chunked and fixed-length bodies, and a streaming mode for SSE.
 */
public final class UdsHttp {

    private UdsHttp() {
    }

    /**
     * A complete response.
     *
     * @param status  the status code
     * @param headers the headers, names in lower case
     * @param body    the body as UTF-8
     */
    public record Response(int status, Map<String, String> headers, String body) {
    }

    /**
     * An open streaming response; the body is read line by line.
     */
    public static final class Stream implements AutoCloseable {

        private final SocketChannel channel;
        private final int status;
        private final Map<String, String> headers;
        private final BufferedReader reader;

        Stream(SocketChannel channel, int status, Map<String, String> headers, InputStream body) {
            this.channel = channel;
            this.status = status;
            this.headers = headers;
            this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        }

        /** @return the status code */
        public int status() {
            return status;
        }

        /** @return the headers, names in lower case */
        public Map<String, String> headers() {
            return headers;
        }

        /**
         * @return the next line of the body, or {@code null} at its end
         * @throws IOException if reading fails
         */
        public String readLine() throws IOException {
            return reader.readLine();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /**
     * Sends one request and reads the whole response.
     *
     * @param socket  the socket path
     * @param method  the HTTP method
     * @param path    the request target
     * @param headers additional headers
     * @param body    the body, or {@code null}
     * @return the response
     * @throws IOException if the exchange fails
     */
    public static Response request(Path socket, String method, String path, Map<String, String> headers,
            String body) throws IOException {
        return request(UnixDomainSocketAddress.of(socket), method, path, headers, body);
    }

    /**
     * Sends one request to any socket address (Unix or TCP) and reads the whole response.
     *
     * @param address the socket address
     * @param method  the HTTP method
     * @param path    the request target
     * @param headers additional headers; a {@code Host} header replaces the default {@code localhost}
     * @param body    the body, or {@code null}
     * @return the response
     * @throws IOException if the exchange fails
     */
    public static Response request(SocketAddress address, String method, String path, Map<String, String> headers,
            String body) throws IOException {
        try (var stream = open(address, method, path, headers, body)) {
            var text = new StringBuilder();
            var buffer = new char[4096];
            for (int n = stream.reader.read(buffer); n >= 0; n = stream.reader.read(buffer)) {
                text.append(buffer, 0, n);
            }
            return new Response(stream.status, stream.headers, text.toString());
        }
    }

    /**
     * Sends one request and returns the response with its body still open.
     *
     * @param socket  the socket path
     * @param method  the HTTP method
     * @param path    the request target
     * @param headers additional headers
     * @param body    the body, or {@code null}
     * @return the open response
     * @throws IOException if the exchange fails
     */
    public static Stream open(Path socket, String method, String path, Map<String, String> headers, String body)
            throws IOException {
        return open(UnixDomainSocketAddress.of(socket), method, path, headers, body);
    }

    /**
     * Sends one request to any socket address and returns the response with its body still open.
     *
     * @param address the socket address
     * @param method  the HTTP method
     * @param path    the request target
     * @param headers additional headers; a {@code Host} header replaces the default {@code localhost}
     * @param body    the body, or {@code null}
     * @return the open response
     * @throws IOException if the exchange fails
     */
    public static Stream open(SocketAddress address, String method, String path, Map<String, String> headers,
            String body) throws IOException {
        var channel = SocketChannel.open(address);
        var bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        var request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Connection: close\r\n");
        if (!headers.containsKey("Host")) {
            request.append("Host: localhost\r\n");
        }
        headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        if (body != null) {
            request.append("Content-Length: ").append(bytes.length).append("\r\n");
        }
        request.append("\r\n");
        var out = Channels.newOutputStream(channel);
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
        var in = Channels.newInputStream(channel);
        var statusLine = line(in);
        var status = Integer.parseInt(statusLine.split(" ")[1]);
        var responseHeaders = new LinkedHashMap<String, String>();
        for (var line = line(in); !line.isEmpty(); line = line(in)) {
            var colon = line.indexOf(':');
            responseHeaders.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).strip());
        }
        InputStream bodyStream;
        if ("chunked".equalsIgnoreCase(responseHeaders.get("transfer-encoding"))) {
            bodyStream = new Chunked(in);
        } else if (responseHeaders.containsKey("content-length")) {
            bodyStream = new ByteArrayInputStream(
                    in.readNBytes(Integer.parseInt(responseHeaders.get("content-length"))));
        } else {
            bodyStream = in;
        }
        return new Stream(channel, status, responseHeaders, bodyStream);
    }

    private static String line(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != '\n') {
            if (b < 0) {
                throw new EOFException("connection closed in the response head");
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    /** Decodes a chunked body, returning bytes as they arrive. */
    private static final class Chunked extends InputStream {

        private final InputStream in;
        private long remaining;
        private boolean done;

        Chunked(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            var one = new byte[1];
            var n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (done) {
                return -1;
            }
            if (remaining == 0) {
                var size = line(in);
                var semicolon = size.indexOf(';');
                remaining = Long.parseLong((semicolon < 0 ? size : size.substring(0, semicolon)).strip(), 16);
                if (remaining == 0) {
                    done = true;
                    return -1;
                }
            }
            var n = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (n < 0) {
                done = true;
                return -1;
            }
            remaining -= n;
            if (remaining == 0) {
                line(in);
            }
            return n;
        }
    }
}
