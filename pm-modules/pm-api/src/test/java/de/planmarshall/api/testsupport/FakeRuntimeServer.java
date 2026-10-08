/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.testsupport;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An in-test fake of the runtime's socket listener: a JDK {@link ServerSocketChannel} on a
 * {@link UnixDomainSocketAddress} that speaks enough HTTP/1.1 (one request per connection,
 * {@code Content-Length} bodies) and Server-Sent Events (chunked, flushed per event) for the client
 * tests. Every request is recorded with its arrival time.
 */
public final class FakeRuntimeServer implements AutoCloseable {

    /** Handles one request. */
    @FunctionalInterface
    public interface Handler {
        /**
         * @param request  the request
         * @param response the response to write
         * @throws IOException on a write failure
         */
        void handle(Request request, Response response) throws IOException;
    }

    /**
     * One received request.
     *
     * @param method        the method
     * @param target        the request target
     * @param headers       the headers, names in lower case
     * @param body          the body
     * @param receivedNanos {@link System#nanoTime()} when the head was read
     */
    public record Request(String method, String target, Map<String, String> headers, byte[] body,
    long receivedNanos) {

        /**
         * @param name a header name
         * @return its value or {@code null}
         */
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** @return the body as UTF-8 text */
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** The response of one exchange. */
    public static final class Response {
        private final OutputStream out;
        private boolean committed;

        Response(OutputStream out) {
            this.out = out;
        }

        /**
         * Writes a complete response with {@code Content-Length}.
         *
         * @param status      the status
         * @param contentType the content type, {@code null} for none
         * @param body        the body
         * @throws IOException on a write failure
         */
        public void send(int status, String contentType, String body) throws IOException {
            var bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            var head = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
            if (contentType != null) {
                head.append("Content-Type: ").append(contentType).append("\r\n");
            }
            head.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
            write(head.toString());
            out.write(bytes);
            out.flush();
        }

        /**
         * @param status the status
         * @param json   the JSON body
         * @throws IOException on a write failure
         */
        public void json(int status, String json) throws IOException {
            send(status, "application/json", json);
        }

        /**
         * Writes a response with the chunked transfer coding, the body split into chunks of the
         * given size.
         *
         * @param status      the status
         * @param contentType the content type
         * @param body        the body
         * @param chunkSize   the chunk size
         * @throws IOException on a write failure
         */
        public void chunked(int status, String contentType, String body, int chunkSize) throws IOException {
            write("HTTP/1.1 " + status + " X\r\nContent-Type: " + contentType
                    + "\r\nTransfer-Encoding: chunked\r\n\r\n");
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            for (var offset = 0; offset < bytes.length; offset += chunkSize) {
                var length = Math.min(chunkSize, bytes.length - offset);
                write(Integer.toHexString(length) + ";ext=1\r\n");
                out.write(bytes, offset, length);
                write("\r\n");
            }
            write("0\r\nX-Trailer: t\r\n\r\n");
            out.flush();
        }

        /**
         * Starts a {@code text/event-stream} response.
         *
         * @param status the status
         * @return the stream
         * @throws IOException on a write failure
         */
        public EventStream events(int status) throws IOException {
            write("HTTP/1.1 " + status + " X\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n");
            out.flush();
            return new EventStream(this);
        }

        /**
         * Writes raw bytes, for malformed responses.
         *
         * @param raw the bytes
         * @throws IOException on a write failure
         */
        public void raw(String raw) throws IOException {
            write(raw);
            out.flush();
        }

        private void write(String text) throws IOException {
            committed = true;
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** An open event stream; every event is flushed in its own chunk. */
    public static final class EventStream implements AutoCloseable {
        private final Response response;

        EventStream(Response response) {
            this.response = response;
        }

        /**
         * @param event the event type, {@code null} for none
         * @param data  the data, one {@code data:} line per line
         * @throws IOException on a write failure
         */
        public void send(String event, String data) throws IOException {
            var frame = new StringBuilder();
            if (event != null) {
                frame.append("event: ").append(event).append('\n');
            }
            for (var line : data.split("\n", -1)) {
                frame.append("data: ").append(line).append('\n');
            }
            frame.append('\n');
            chunk(frame.toString());
        }

        /**
         * @param frame raw event-stream text in one chunk
         * @throws IOException on a write failure
         */
        public void chunk(String frame) throws IOException {
            var bytes = frame.getBytes(StandardCharsets.UTF_8);
            response.write(Integer.toHexString(bytes.length) + "\r\n");
            response.out.write(bytes);
            response.write("\r\n");
            response.out.flush();
        }

        @Override
        public void close() throws IOException {
            response.write("0\r\n\r\n");
            response.out.flush();
        }
    }

    private final Path socket;
    private final ServerSocketChannel server;
    private final Handler handler;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Request> arrivals = new LinkedBlockingQueue<>();
    private final Thread acceptor;

    private FakeRuntimeServer(Path socket, Handler handler) throws IOException {
        this.socket = socket;
        this.handler = handler;
        Files.deleteIfExists(socket);
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        this.acceptor = Thread.ofPlatform().daemon().name("fake-runtime-acceptor").start(this::acceptLoop);
    }

    /**
     * Binds the socket and starts accepting.
     *
     * @param socket  the socket path
     * @param handler the request handler
     * @return the running server
     * @throws IOException if the socket cannot be bound
     */
    public static FakeRuntimeServer start(Path socket, Handler handler) throws IOException {
        return new FakeRuntimeServer(socket, handler);
    }

    /** @return every request received so far */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /**
     * @param timeout the maximum wait
     * @return the next request in arrival order, {@code null} on timeout
     * @throws InterruptedException if interrupted
     */
    public Request awaitRequest(Duration timeout) throws InterruptedException {
        return arrivals.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            try {
                var channel = server.accept();
                Thread.ofVirtual().start(() -> serve(channel));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(SocketChannel channel) {
        try (channel) {
            var in = new BufferedInputStream(Channels.newInputStream(channel));
            var out = Channels.newOutputStream(channel);
            var request = readRequest(in);
            if (request == null) {
                return;
            }
            requests.add(request);
            arrivals.add(request);
            var response = new Response(out);
            handler.handle(request, response);
            if (!response.committed) {
                response.send(500, null, "");
            }
        } catch (IOException e) {
            // the client closed the connection
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        var requestLine = line(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        var received = System.nanoTime();
        var parts = requestLine.split(" ");
        var headers = new LinkedHashMap<String, String>();
        String header;
        while ((header = line(in)) != null && !header.isEmpty()) {
            var colon = header.indexOf(':');
            headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
        }
        var length = headers.containsKey("content-length") ? Integer.parseInt(headers.get("content-length")) : 0;
        return new Request(parts[0], parts[1], headers, in.readNBytes(length), received);
    }

    private static String line(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            bytes.write(b);
        }
        if (b < 0 && bytes.size() == 0) {
            return null;
        }
        var text = bytes.toString(StandardCharsets.UTF_8);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    @Override
    public void close() throws IOException {
        server.close();
        acceptor.interrupt();
        Files.deleteIfExists(socket);
    }
}
