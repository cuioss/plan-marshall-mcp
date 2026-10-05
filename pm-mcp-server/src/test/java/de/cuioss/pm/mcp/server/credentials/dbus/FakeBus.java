/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.credentials.dbus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake message bus on a JDK Unix domain socket: answers SASL {@code EXTERNAL}, {@code Hello}
 * (preceded by a {@code NameAcquired} signal, as a real bus sends), and dispatches every other method
 * call to a {@link Handler}. Usable on macOS and Linux.
 */
public final class FakeBus implements AutoCloseable {

    /** Answers one method call. */
    @FunctionalInterface
    public interface Handler {

        /**
         * @param call the method call
         * @return the reply: a {@link Reply} or an {@link ErrorReply}; {@code null} for no reply at all
         */
        Object handle(DbusMessage call);
    }

    /** A method return. */
    public record Reply(String signature, List<Object> body) {
    }

    /** An error reply. */
    public record ErrorReply(String name, String message) {
    }

    private final ServerSocketChannel server;
    private final Handler handler;
    private final Thread thread;
    private final String expectedIdentity;
    private final List<String> authLines = new CopyOnWriteArrayList<>();
    private final List<DbusMessage> calls = new CopyOnWriteArrayList<>();
    private long serial = 100;

    /**
     * @param socket  the socket path to bind
     * @param uid     the uid the client must authenticate with
     * @param handler the method handler
     * @throws IOException if the socket cannot be bound
     */
    public FakeBus(Path socket, long uid, Handler handler) throws IOException {
        Files.deleteIfExists(socket);
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        this.handler = handler;
        this.expectedIdentity = HexFormat.of()
                .formatHex(Long.toString(uid).getBytes(StandardCharsets.US_ASCII));
        this.thread = Thread.ofVirtual().start(this::serve);
    }

    /** @return the SASL lines received */
    public List<String> authLines() {
        return authLines;
    }

    /** @return the method calls received, {@code Hello} included */
    public List<DbusMessage> calls() {
        return calls;
    }

    private void serve() {
        while (server.isOpen()) {
            try (var client = server.accept()) {
                serveClient(client);
            } catch (IOException | DbusException _) {
                // client gone or server closed
            }
        }
    }

    private void serveClient(SocketChannel client) throws IOException {
        if (readByte(client) != 0) {
            return;
        }
        var auth = readLine(client);
        authLines.add(auth);
        if (!auth.equals("AUTH EXTERNAL " + expectedIdentity)) {
            write(client, "REJECTED EXTERNAL\r\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        write(client, "OK 0123456789abcdef0123456789abcdef\r\n".getBytes(StandardCharsets.US_ASCII));
        authLines.add(readLine(client));
        while (true) {
            var fixed = readFully(client, DbusMessage.FIXED_HEADER);
            var rest = readFully(client, DbusMessage.totalLength(fixed) - DbusMessage.FIXED_HEADER);
            var bytes = new byte[fixed.length + rest.length];
            System.arraycopy(fixed, 0, bytes, 0, fixed.length);
            System.arraycopy(rest, 0, bytes, fixed.length, rest.length);
            var call = DbusMessage.decode(bytes);
            calls.add(call);
            if ("Hello".equals(call.member())) {
                write(client, signal());
                write(client, reply(call, new Reply("s", List.of(":1.7"))));
                continue;
            }
            var answer = handler.handle(call);
            if (answer != null) {
                write(client, reply(call, answer));
            }
        }
    }

    private byte[] signal() {
        return new DbusMessage(DbusMessage.SIGNAL, 0, ++serial, "/org/freedesktop/DBus", "org.freedesktop.DBus",
                "NameAcquired", null, 0, ":1.7", "org.freedesktop.DBus", "s", List.of(":1.7")).encode();
    }

    private byte[] reply(DbusMessage call, Object answer) {
        if (answer instanceof ErrorReply error) {
            return new DbusMessage(DbusMessage.ERROR, 0, ++serial, null, null, null, error.name(), call.serial(),
                    ":1.7", "org.freedesktop.secrets", "s", List.of(error.message())).encode();
        }
        var reply = (Reply) answer;
        return new DbusMessage(DbusMessage.METHOD_RETURN, 0, ++serial, null, null, null, null, call.serial(), ":1.7",
                "org.freedesktop.secrets", reply.signature(), new ArrayList<>(reply.body())).encode();
    }

    private static int readByte(SocketChannel client) throws IOException {
        return readFully(client, 1)[0];
    }

    private static String readLine(SocketChannel client) throws IOException {
        var line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int b = readByte(client);
            if (previous == '\r' && b == '\n') {
                var bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            line.write(b);
            previous = b;
        }
    }

    private static byte[] readFully(SocketChannel client, int length) throws IOException {
        var buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (client.read(buffer) < 0) {
                throw new IOException("client closed");
            }
        }
        return buffer.array();
    }

    private static void write(SocketChannel client, byte[] bytes) throws IOException {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            client.write(buffer);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        thread.interrupt();
    }
}
