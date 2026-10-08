/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.credentials.dbus;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A blocking D-Bus client connection over the JDK's Unix domain socket channel: SASL
 * {@code EXTERNAL} authentication with the process uid, {@code Hello}, and method calls whose
 * replies are matched by serial (signals and other messages in between are skipped). Every call is
 * bounded by the connection's timeout, so an unresponsive peer never hangs the caller.
 */
public final class DbusConnection implements AutoCloseable {

    /** Bus name, path and interface of the message bus itself. */
    public static final String BUS = "org.freedesktop.DBus";
    private static final String BUS_PATH = "/org/freedesktop/DBus";
    private static final String CANNOT_CONNECT = "cannot connect to the bus at ";

    private final SocketChannel channel;
    private final Selector selector;
    private final SelectionKey key;
    private final Duration timeout;
    private long serial;
    private String uniqueName;

    private DbusConnection(SocketChannel channel, Duration timeout) throws IOException {
        this.channel = channel;
        this.timeout = timeout;
        this.selector = Selector.open();
        try {
            channel.configureBlocking(false);
            this.key = channel.register(selector, 0);
        } catch (IOException e) {
            closeQuietly(selector);
            throw e;
        }
    }

    /**
     * Connects, authenticates and says {@code Hello}.
     *
     * @param socket  the bus socket
     * @param uid     the uid of this process, sent as SASL {@code EXTERNAL} identity
     * @param timeout the bound of every exchange
     * @return the connection
     * @throws DbusException if the bus cannot be reached, refuses the authentication or does not answer
     */
    public static DbusConnection open(Path socket, long uid, Duration timeout) {
        var connection = connect(socket, timeout);
        try {
            connection.authenticate(uid);
            connection.uniqueName = (String) connection.call(BUS, BUS_PATH, BUS, "Hello", "", List.of()).getFirst();
            return connection;
        } catch (IOException e) {
            connection.close();
            throw new DbusException(CANNOT_CONNECT + socket, e);
        } catch (DbusException e) {
            connection.close();
            throw e;
        }
    }

    private static DbusConnection connect(Path socket, Duration timeout) {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            return new DbusConnection(channel, timeout);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new DbusException(CANNOT_CONNECT + socket, e);
        }
    }

    /**
     * Resolves the session bus socket the way {@code libdbus} does: the first {@code unix:path=}
     * address of {@code DBUS_SESSION_BUS_ADDRESS}, otherwise {@code $XDG_RUNTIME_DIR/bus}. Abstract
     * socket addresses ({@code unix:abstract=}) cannot be expressed as a JDK
     * {@link UnixDomainSocketAddress} (a path cannot contain the leading NUL byte) and are skipped.
     *
     * @param environment the process environment
     * @return the socket path, or empty if no usable address exists
     */
    public static Optional<Path> sessionBusSocket(Map<String, String> environment) {
        var address = environment.get("DBUS_SESSION_BUS_ADDRESS");
        if (address != null) {
            for (var entry : address.split(";")) {
                if (!entry.startsWith("unix:")) {
                    continue;
                }
                for (var pair : entry.substring(5).split(",")) {
                    if (pair.startsWith("path=")) {
                        return Optional.of(Path.of(unescape(pair.substring(5))));
                    }
                }
            }
            return Optional.empty();
        }
        var runtimeDir = environment.get("XDG_RUNTIME_DIR");
        return runtimeDir == null ? Optional.empty() : Optional.of(Path.of(runtimeDir, "bus"));
    }

    private static String unescape(String value) {
        var bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                bytes.write(HexFormat.fromHexDigits(value, i + 1, i + 3));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** @return the unique bus name {@code Hello} assigned */
    public String uniqueName() {
        return uniqueName;
    }

    /**
     * Calls a method and waits for its reply.
     *
     * @param destination the bus name of the peer
     * @param path        the object path
     * @param iface       the interface
     * @param member      the method
     * @param signature   the argument signature
     * @param args        the arguments
     * @return the reply body
     * @throws DbusException on an {@code ERROR} reply (with its error name), a malformed reply, a
     *                       transport failure or the timeout
     */
    public List<Object> call(String destination, String path, String iface, String member, String signature,
            List<?> args) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long callSerial = ++serial;
        try {
            writeFully(ByteBuffer.wrap(DbusMessage.methodCall(callSerial, destination, path, iface, member, signature,
                    args).encode()), deadline);
            while (true) {
                var fixed = readFully(DbusMessage.FIXED_HEADER, deadline);
                var rest = readFully(DbusMessage.totalLength(fixed) - DbusMessage.FIXED_HEADER, deadline);
                var bytes = new byte[fixed.length + rest.length];
                System.arraycopy(fixed, 0, bytes, 0, fixed.length);
                System.arraycopy(rest, 0, bytes, fixed.length, rest.length);
                var reply = DbusMessage.decode(bytes);
                if (reply.replySerial() != callSerial) {
                    continue;
                }
                if (reply.type() == DbusMessage.ERROR) {
                    var text = reply.body().isEmpty() ? "" : String.valueOf(reply.body().getFirst());
                    throw new DbusException(reply.errorName(), member + ": " + text);
                }
                return reply.body();
            }
        } catch (IOException e) {
            throw new DbusException(member + " failed: " + e.getMessage(), e);
        }
    }

    private void authenticate(long uid) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        var identity = HexFormat.of().formatHex(Long.toString(uid).getBytes(StandardCharsets.US_ASCII));
        writeFully(ByteBuffer.wrap(("\0AUTH EXTERNAL " + identity + "\r\n").getBytes(StandardCharsets.US_ASCII)),
                deadline);
        var line = readLine(deadline);
        if (!line.startsWith("OK ")) {
            throw new DbusException("org.freedesktop.DBus.Error.AuthFailed", "SASL EXTERNAL refused: " + line);
        }
        writeFully(ByteBuffer.wrap("BEGIN\r\n".getBytes(StandardCharsets.US_ASCII)), deadline);
    }

    private String readLine(long deadline) throws IOException {
        var sb = new StringBuilder();
        while (sb.length() < 2 || sb.charAt(sb.length() - 2) != '\r' || sb.charAt(sb.length() - 1) != '\n') {
            sb.append((char) readFully(1, deadline)[0]);
            if (sb.length() > 512) {
                throw new DbusException(DbusException.LOCAL, "SASL line too long");
            }
        }
        return sb.substring(0, sb.length() - 2);
    }

    private byte[] readFully(int length, long deadline) throws IOException {
        var buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new DbusException(DbusException.LOCAL, "bus closed the connection");
            }
            if (read == 0) {
                await(SelectionKey.OP_READ, deadline);
            }
        }
        return buffer.array();
    }

    private void writeFully(ByteBuffer buffer, long deadline) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) == 0) {
                await(SelectionKey.OP_WRITE, deadline);
            }
        }
    }

    private void await(int operation, long deadline) throws IOException {
        long remaining = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
        if (remaining <= 0) {
            throw new DbusException(DbusException.LOCAL, "no answer within " + timeout.toMillis() + " ms");
        }
        key.interestOps(operation);
        selector.select(remaining);
        selector.selectedKeys().clear();
        key.interestOps(0);
    }

    @Override
    public void close() {
        closeQuietly(selector);
        closeQuietly(channel);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException _) {
            // nothing left to release
        }
    }
}
