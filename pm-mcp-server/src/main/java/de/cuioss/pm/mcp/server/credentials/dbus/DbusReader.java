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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Unmarshals values from the D-Bus wire format, in the byte order the message declares. Offsets
 * are relative to the buffer's position 0, an 8-byte boundary of the message.
 */
public final class DbusReader {

    private final ByteBuffer buffer;

    /**
     * @param bytes the bytes, starting at an 8-byte boundary of the message
     * @param order the byte order of the message
     */
    public DbusReader(byte[] bytes, ByteOrder order) {
        this.buffer = ByteBuffer.wrap(bytes).order(order);
    }

    /**
     * Reads values of a signature.
     *
     * @param signature zero or more complete types
     * @return one value per complete type
     * @throws DbusException if the bytes are malformed
     */
    public List<Object> read(String signature) {
        var values = new ArrayList<>();
        try {
            for (var type : Signatures.split(signature)) {
                values.add(readValue(type));
            }
        } catch (BufferUnderflowException | IndexOutOfBoundsException | ClassCastException e) {
            throw new DbusException("malformed D-Bus data for signature " + signature, e);
        }
        return values;
    }

    /** @return the current offset */
    int position() {
        return buffer.position();
    }

    /**
     * Skips padding to the given boundary.
     *
     * @param alignment the boundary in bytes
     */
    void align(int alignment) {
        int misaligned = buffer.position() % alignment;
        if (misaligned != 0) {
            buffer.position(buffer.position() + alignment - misaligned);
        }
    }

    private Object readValue(String type) {
        return switch (type.charAt(0)) {
            case 'y' -> buffer.get();
            case 'b' -> uint32() != 0;
            case 'n' -> {
                align(2);
                yield buffer.getShort();
            }
            case 'q' -> {
                align(2);
                yield Short.toUnsignedInt(buffer.getShort());
            }
            case 'i', 'h' -> int32();
            case 'u' -> uint32();
            case 'x', 't' -> {
                align(8);
                yield buffer.getLong();
            }
            case 's', 'o' -> string((int) uint32());
            case 'g' -> string(Byte.toUnsignedInt(buffer.get()));
            case 'v' -> {
                var signature = (String) readValue("g");
                yield new Variant(signature, readValue(signature));
            }
            case 'a' -> readArray(type.substring(1));
            case '(' -> {
                align(8);
                var fields = new ArrayList<>();
                for (var field : Signatures.split(type.substring(1, type.length() - 1))) {
                    fields.add(readValue(field));
                }
                yield fields;
            }
            default -> throw new DbusException(DbusException.LOCAL, "unsupported type " + type);
        };
    }

    private Object readArray(String elementType) {
        long length = uint32();
        align(Signatures.alignment(elementType));
        int end = Math.toIntExact(buffer.position() + length);
        if (end > buffer.limit()) {
            throw new DbusException(DbusException.LOCAL, "array exceeds the message");
        }
        if ("y".equals(elementType)) {
            var bytes = new byte[(int) length];
            buffer.get(bytes);
            return bytes;
        }
        if (elementType.charAt(0) == '{') {
            var inner = Signatures.split(elementType.substring(1, elementType.length() - 1));
            var map = new LinkedHashMap<>();
            while (buffer.position() < end) {
                align(8);
                map.put(readValue(inner.get(0)), readValue(inner.get(1)));
            }
            return map;
        }
        var list = new ArrayList<>();
        while (buffer.position() < end) {
            list.add(readValue(elementType));
        }
        return list;
    }

    private int int32() {
        align(4);
        return buffer.getInt();
    }

    private long uint32() {
        return Integer.toUnsignedLong(int32());
    }

    private String string(int length) {
        var bytes = new byte[length];
        buffer.get(bytes);
        if (buffer.get() != 0) {
            throw new DbusException(DbusException.LOCAL, "string without NUL terminator");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
