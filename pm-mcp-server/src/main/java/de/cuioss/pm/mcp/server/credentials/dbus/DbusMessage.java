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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A D-Bus message: the fixed header, the header fields this client uses, and the body.
 *
 * @param type        {@link #METHOD_CALL}, {@link #METHOD_RETURN}, {@link #ERROR} or {@link #SIGNAL}
 * @param flags       the header flags
 * @param serial      the serial, never {@code 0}
 * @param path        header field 1, or {@code null}
 * @param iface       header field 2, or {@code null}
 * @param member      header field 3, or {@code null}
 * @param errorName   header field 4, or {@code null}
 * @param replySerial header field 5, {@code 0} if absent
 * @param destination header field 6, or {@code null}
 * @param sender      header field 7, or {@code null}
 * @param signature   header field 8, empty for an empty body
 * @param body        the body values, one per complete type of the signature
 */
public record DbusMessage(int type, int flags, long serial, String path, String iface, String member,
String errorName, long replySerial, String destination, String sender, String signature, List<Object> body) {

    /** Message type {@code METHOD_CALL}. */
    public static final int METHOD_CALL = 1;
    /** Message type {@code METHOD_RETURN}. */
    public static final int METHOD_RETURN = 2;
    /** Message type {@code ERROR}. */
    public static final int ERROR = 3;
    /** Message type {@code SIGNAL}. */
    public static final int SIGNAL = 4;
    /** Flag {@code NO_REPLY_EXPECTED}. */
    public static final int NO_REPLY_EXPECTED = 1;
    /** Size of the fixed header plus the header-field array length. */
    public static final int FIXED_HEADER = 16;

    private static final int FIELD_PATH = 1;
    private static final int FIELD_INTERFACE = 2;
    private static final int FIELD_MEMBER = 3;
    private static final int FIELD_ERROR_NAME = 4;
    private static final int FIELD_REPLY_SERIAL = 5;
    private static final int FIELD_DESTINATION = 6;
    private static final int FIELD_SENDER = 7;
    private static final int FIELD_SIGNATURE = 8;

    /**
     * Normalizes absent values.
     *
     * @param type        the type
     * @param flags       the flags
     * @param serial      the serial
     * @param path        the path
     * @param iface       the interface
     * @param member      the member
     * @param errorName   the error name
     * @param replySerial the reply serial
     * @param destination the destination
     * @param sender      the sender
     * @param signature   the body signature
     * @param body        the body
     */
    public DbusMessage {
        signature = signature == null ? "" : signature;
        body = List.copyOf(body);
    }

    /**
     * A method call.
     *
     * @param serial      the serial
     * @param destination the bus name of the peer
     * @param path        the object path
     * @param iface       the interface
     * @param member      the method
     * @param signature   the body signature
     * @param args        the arguments
     * @return the message
     */
    public static DbusMessage methodCall(long serial, String destination, String path, String iface, String member,
            String signature, List<?> args) {
        return new DbusMessage(METHOD_CALL, 0, serial, path, iface, member, null, 0, destination, null, signature,
                new ArrayList<>(args));
    }

    /**
     * Encodes the message in little-endian byte order.
     *
     * @return the wire bytes
     */
    public byte[] encode() {
        var bodyBytes = new DbusWriter().write(signature, body).toByteArray();
        var fields = new ArrayList<List<Object>>();
        addField(fields, FIELD_PATH, "o", path);
        addField(fields, FIELD_INTERFACE, "s", iface);
        addField(fields, FIELD_MEMBER, "s", member);
        addField(fields, FIELD_ERROR_NAME, "s", errorName);
        if (replySerial != 0) {
            addField(fields, FIELD_REPLY_SERIAL, "u", replySerial);
        }
        addField(fields, FIELD_DESTINATION, "s", destination);
        addField(fields, FIELD_SENDER, "s", sender);
        if (!signature.isEmpty()) {
            addField(fields, FIELD_SIGNATURE, "g", signature);
        }
        var header = new DbusWriter().write("yyyyuua(yv)",
                List.of((byte) 'l', type, flags, 1, bodyBytes.length, serial, fields));
        header.align(8);
        var headerBytes = header.toByteArray();
        var message = Arrays.copyOf(headerBytes, headerBytes.length + bodyBytes.length);
        System.arraycopy(bodyBytes, 0, message, headerBytes.length, bodyBytes.length);
        return message;
    }

    private static void addField(List<List<Object>> fields, int code, String type, Object value) {
        if (value != null) {
            fields.add(List.of((byte) code, new Variant(type, value)));
        }
    }

    /**
     * @param fixedHeader the first {@link #FIXED_HEADER} bytes of a message
     * @return the length of the whole message
     * @throws DbusException for an unknown byte order
     */
    public static int totalLength(byte[] fixedHeader) {
        var buffer = ByteBuffer.wrap(fixedHeader).order(order(fixedHeader[0]));
        long bodyLength = Integer.toUnsignedLong(buffer.getInt(4));
        long fieldsLength = Integer.toUnsignedLong(buffer.getInt(12));
        long headerLength = (FIXED_HEADER + fieldsLength + 7) & ~7L;
        return Math.toIntExact(headerLength + bodyLength);
    }

    /**
     * Decodes a complete message.
     *
     * @param bytes the wire bytes of one message
     * @return the message
     * @throws DbusException if the message is malformed
     */
    public static DbusMessage decode(byte[] bytes) {
        var order = order(bytes[0]);
        if (bytes.length < FIXED_HEADER || bytes.length != totalLength(bytes)) {
            throw new DbusException(DbusException.LOCAL, "message length does not match its header");
        }
        var header = new DbusReader(bytes, order).read("yyyyuua(yv)");
        int type = Byte.toUnsignedInt((Byte) header.get(1));
        int flags = Byte.toUnsignedInt((Byte) header.get(2));
        long bodyLength = (Long) header.get(4);
        long serial = (Long) header.get(5);
        String path = null;
        String iface = null;
        String member = null;
        String errorName = null;
        long replySerial = 0;
        String destination = null;
        String sender = null;
        String signature = "";
        for (var field : (List<?>) header.get(6)) {
            var entry = (List<?>) field;
            var value = ((Variant) entry.get(1)).value();
            switch ((Byte) entry.get(0)) {
                case FIELD_PATH -> path = (String) value;
                case FIELD_INTERFACE -> iface = (String) value;
                case FIELD_MEMBER -> member = (String) value;
                case FIELD_ERROR_NAME -> errorName = (String) value;
                case FIELD_REPLY_SERIAL -> replySerial = (Long) value;
                case FIELD_DESTINATION -> destination = (String) value;
                case FIELD_SENDER -> sender = (String) value;
                case FIELD_SIGNATURE -> signature = (String) value;
                default -> {
                    // unknown header fields are ignored, as the specification requires
                }
            }
        }
        int bodyStart = totalLength(bytes) - Math.toIntExact(bodyLength);
        var bodyBytes = Arrays.copyOfRange(bytes, bodyStart, bodyStart + Math.toIntExact(bodyLength));
        var body = new DbusReader(bodyBytes, order).read(signature);
        return new DbusMessage(type, flags, serial, path, iface, member, errorName, replySerial, destination, sender,
                signature, body);
    }

    private static ByteOrder order(byte marker) {
        return switch (marker) {
            case 'l' -> ByteOrder.LITTLE_ENDIAN;
            case 'B' -> ByteOrder.BIG_ENDIAN;
            default -> throw new DbusException(DbusException.LOCAL, "unknown byte order marker " + marker);
        };
    }
}
