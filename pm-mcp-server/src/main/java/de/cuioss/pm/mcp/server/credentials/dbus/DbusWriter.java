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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Marshals values into the little-endian D-Bus wire format. Offsets are relative to the start of
 * this writer, which must therefore start at an 8-byte boundary of the message (the header and the
 * body both do).
 */
public final class DbusWriter {

    private byte[] buffer = new byte[256];
    private int position;

    /**
     * Writes values of a signature.
     *
     * @param signature zero or more complete types
     * @param values    one value per complete type
     * @return this writer
     * @throws DbusException if the values do not match the signature
     */
    public DbusWriter write(String signature, List<?> values) {
        var types = Signatures.split(signature);
        if (types.size() != values.size()) {
            throw new DbusException(DbusException.LOCAL,
                    "signature " + signature + " needs " + types.size() + " values, got " + values.size());
        }
        for (int i = 0; i < types.size(); i++) {
            writeValue(types.get(i), values.get(i));
        }
        return this;
    }

    /** @return the bytes written so far */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, position);
    }

    /** @return the number of bytes written so far */
    public int size() {
        return position;
    }

    /**
     * Pads with zero bytes to the given boundary.
     *
     * @param alignment the boundary in bytes
     */
    void align(int alignment) {
        while (position % alignment != 0) {
            put((byte) 0);
        }
    }

    /**
     * Overwrites a 32-bit value at a position already written.
     *
     * @param at    the position
     * @param value the value
     */
    void patchUint32(int at, int value) {
        ByteBuffer.wrap(buffer, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    @SuppressWarnings("unchecked")
    private void writeValue(String type, Object value) {
        switch (type.charAt(0)) {
            case 'y' -> put(((Number) value).byteValue());
            case 'b' -> uint32(Boolean.TRUE.equals(value) ? 1 : 0);
            case 'n', 'q' -> {
                align(2);
                int v = ((Number) value).intValue();
                put((byte) v);
                put((byte) (v >>> 8));
            }
            case 'i', 'u', 'h' -> uint32(((Number) value).intValue());
            case 'x', 't' -> {
                align(8);
                long v = ((Number) value).longValue();
                uint32((int) v);
                uint32((int) (v >>> 32));
            }
            case 's', 'o' -> {
                var bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                uint32(bytes.length);
                put(bytes);
                put((byte) 0);
            }
            case 'g' -> {
                var bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                put((byte) bytes.length);
                put(bytes);
                put((byte) 0);
            }
            case 'v' -> {
                var variant = (Variant) value;
                writeValue("g", variant.signature());
                writeValue(variant.signature(), variant.value());
            }
            case 'a' -> writeArray(type.substring(1), value);
            case '(' -> {
                align(8);
                var fields = Signatures.split(type.substring(1, type.length() - 1));
                var values = (List<Object>) value;
                for (int i = 0; i < fields.size(); i++) {
                    writeValue(fields.get(i), values.get(i));
                }
            }
            default -> throw new DbusException(DbusException.LOCAL, "unsupported type " + type);
        }
    }

    private void writeArray(String elementType, Object value) {
        align(4);
        int lengthAt = position;
        uint32(0);
        align(Signatures.alignment(elementType));
        int start = position;
        if (elementType.charAt(0) == '{') {
            var inner = Signatures.split(elementType.substring(1, elementType.length() - 1));
            for (var entry : ((Map<?, ?>) value).entrySet()) {
                align(8);
                writeValue(inner.get(0), entry.getKey());
                writeValue(inner.get(1), entry.getValue());
            }
        } else if (value instanceof byte[] bytes) {
            put(bytes);
        } else {
            for (var element : (List<?>) value) {
                writeValue(elementType, element);
            }
        }
        patchUint32(lengthAt, position - start);
    }

    private void uint32(int value) {
        align(4);
        ensure(4);
        ByteBuffer.wrap(buffer, position, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
        position += 4;
    }

    private void put(byte value) {
        ensure(1);
        buffer[position++] = value;
    }

    private void put(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buffer, position, bytes.length);
        position += bytes.length;
    }

    private void ensure(int more) {
        if (position + more > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, position + more));
        }
    }
}
