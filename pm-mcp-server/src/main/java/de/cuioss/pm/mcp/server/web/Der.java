/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.web;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;


import lombok.experimental.UtilityClass;

/**
 * The minimal DER encoder the self-signed certificate needs: the JDK has no public X.509 builder, its internal
 * {@code sun.security.x509} is not exported (and {@code keytool} code is not in a native image), so the
 * certificate is assembled from these primitives.
 *
 * @since 0.1
 */
@UtilityClass
final class Der {

    static final int TAG_SEQUENCE = 0x30;
    static final int TAG_SET = 0x31;
    static final int TAG_INTEGER = 0x02;
    static final int TAG_BIT_STRING = 0x03;
    static final int TAG_OCTET_STRING = 0x04;
    static final int TAG_OID = 0x06;
    static final int TAG_UTF8_STRING = 0x0C;
    static final int TAG_UTC_TIME = 0x17;
    static final int TAG_BOOLEAN = 0x01;

    /**
     * @param tag      the tag byte
     * @param contents the encoded contents
     * @return the tag-length-value encoding
     */
    static byte[] tlv(int tag, byte[]... contents) {
        var body = concat(contents);
        var out = new ByteArrayOutputStream();
        out.write(tag);
        var length = body.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            var bytes = BigInteger.valueOf(length).toByteArray();
            var start = bytes[0] == 0 ? 1 : 0;
            out.write(0x80 | (bytes.length - start));
            out.write(bytes, start, bytes.length - start);
        }
        out.writeBytes(body);
        return out.toByteArray();
    }

    static byte[] sequence(byte[]... contents) {
        return tlv(TAG_SEQUENCE, contents);
    }

    static byte[] integer(BigInteger value) {
        return tlv(TAG_INTEGER, value.toByteArray());
    }

    static byte[] utf8(String value) {
        return tlv(TAG_UTF8_STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    /** A UTCTime ({@code yyMMddHHmmssZ}), the form RFC 5280 requires for dates before 2050. */
    static byte[] utcTime(String yyMMddHHmmssZ) {
        return tlv(TAG_UTC_TIME, yyMMddHHmmssZ.getBytes(StandardCharsets.US_ASCII));
    }

    /** A BIT STRING without unused bits. */
    static byte[] bitString(byte[] bits) {
        return tlv(TAG_BIT_STRING, new byte[]{0}, bits);
    }

    /**
     * @param dotted an object identifier such as {@code 1.2.840.10045.4.3.2}
     * @return its DER encoding
     */
    static byte[] oid(String dotted) {
        var parts = dotted.split("\\.");
        var out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            var value = Long.parseLong(parts[i]);
            var stack = new ByteArrayOutputStream();
            stack.write((int) (value & 0x7F));
            value >>>= 7;
            while (value > 0) {
                stack.write((int) (value & 0x7F) | 0x80);
                value >>>= 7;
            }
            var reversed = stack.toByteArray();
            for (int j = reversed.length - 1; j >= 0; j--) {
                out.write(reversed[j]);
            }
        }
        return tlv(TAG_OID, out.toByteArray());
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
