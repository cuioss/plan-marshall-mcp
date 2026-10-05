/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A minimal HTTP/1.1 client over the runtime's Unix domain socket: one request per connection, fixed-length and
 * chunked bodies, runtime token as Bearer.
 */
final class UdsClient {

    /**
     * A response.
     *
     * @param status the status code
     * @param body   the body as UTF-8
     */
    record Response(int status, String body) {
    }

    private final Path socket;
    private final String token;

    UdsClient(Path socket, String token) {
        this.socket = socket;
        this.token = token;
    }

    /**
     * @param socket a socket path
     * @return whether a connection is accepted
     */
    static boolean accepts(Path socket) {
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * @param method the method
     * @param path   the request target
     * @param json   the JSON body, or {@code null}
     * @return the response
     * @throws IOException if the exchange fails
     */
    Response send(String method, String path, String json) throws IOException {
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            var body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
            var head = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: localhost\r\nConnection: close\r\nAccept: application/json\r\n")
                    .append("Authorization: Bearer ").append(token).append("\r\n");
            if (json != null) {
                head.append("Content-Type: application/json\r\nContent-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            var out = Channels.newOutputStream(channel);
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            var in = Channels.newInputStream(channel);
            var status = Integer.parseInt(line(in).split(" ")[1]);
            var length = -1;
            var chunked = false;
            for (var header = line(in); !header.isEmpty(); header = line(in)) {
                var lower = header.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    length = Integer.parseInt(lower.substring(15).strip());
                } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    chunked = true;
                }
            }
            byte[] content;
            if (chunked) {
                var buffer = new ByteArrayOutputStream();
                for (var size = Integer.parseInt(line(in).split(";")[0].strip(), 16); size > 0;
                        size = Integer.parseInt(line(in).split(";")[0].strip(), 16)) {
                    buffer.write(in.readNBytes(size));
                    line(in);
                }
                content = buffer.toByteArray();
            } else if (length >= 0) {
                content = in.readNBytes(length);
            } else {
                content = in.readAllBytes();
            }
            return new Response(status, new String(content, StandardCharsets.UTF_8));
        }
    }

    private static String line(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        for (var b = in.read(); b != '\n'; b = in.read()) {
            if (b < 0) {
                throw new EOFException("connection closed");
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }
}
