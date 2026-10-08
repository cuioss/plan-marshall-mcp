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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Byte-exact tests against message dumps constructed field by field from the D-Bus specification
 * (little-endian, header fields in ascending code order), independently of the marshaller.
 */
@DisplayName("DbusMessage: wire format")
class DbusMessageTest {

    /** {@code Hello} to the bus, serial 1: 128 bytes, header-field array of 109 bytes. */
    static final String HELLO = "6c01000100000000010000006d00000001016f00150000002f6f72672f667265656465736b746f702f"
            + "4442757300000002017300140000006f72672e667265656465736b746f702e44427573000000000301730005000000"
            + "48656c6c6f00000006017300140000006f72672e667265656465736b746f702e4442757300000000";

    /** {@code OpenSession("plain", <s "">)}, serial 2, signature {@code sv}. */
    static final String OPEN_SESSION = "6c01000115000000020000009000000001016f00180000002f6f72672f6672656564657"
            + "36b746f702f736563726574730000000000000000020173001e0000006f72672e667265656465736b746f702e536563"
            + "7265742e536572766963650000030173000b0000004f70656e53657373696f6e000000000006017300170000006f72"
            + "672e667265656465736b746f702e7365637265747300080167000273760005000000706c61696e000173000000000000"
            + "000000";

    /** {@code SearchItems({service, credential_key})}, serial 3, signature {@code a{ss}}. */
    static final String SEARCH = "6c0100014f000000030000009300000001016f00180000002f6f72672f667265656465736b746"
            + "f702f736563726574730000000000000000020173001e0000006f72672e667265656465736b746f702e5365637265"
            + "742e536572766963650000030173000b0000005365617263684974656d73000000000006017300170000006f72672e"
            + "667265656465736b746f702e73656372657473000801670005617b73737d0000000000004700000000000000070000"
            + "0073657276696365001300000064652e706c616e6d61727368616c6c2e6d637000000000000e00000063726564656e"
            + "7469616c5f6b657900000600000067697468756200";

    /** Reply to {@code Hello}: {@code METHOD_RETURN}, flags 1, serial 7, reply serial 1, body ":1.42". */
    static final String HELLO_REPLY = "6c0201010a000000070000003f00000005017500010000000601730005000000"
            + "3a312e343200000007017300140000006f72672e667265656465736b746f702e4442757300000000080167000173"
            + "0000050000003a312e343200";

    /** Reply to {@code GetSecrets}: {@code a{o(oayays)}} with one item and secret "s3cr3t". */
    static final String SECRETS_REPLY = "6c0200017f000000090000001a0000000501750004000000080167000c617b6f286f61"
            + "79617973297d0000000000000077000000000000002b0000002f6f72672f667265656465736b746f702f736563726574"
            + "732f636f6c6c656374696f6e2f6c6f67696e2f3700230000002f6f72672f667265656465736b746f702f736563726574"
            + "732f73657373696f6e2f733100000000000600000073336372337400000a000000746578742f706c61696e00";

    /** {@code ERROR} reply {@code org.freedesktop.DBus.Error.ServiceUnknown} to serial 2. */
    static final String ERROR_REPLY = "6c03000120000000050000004700000004017300290000006f72672e66726565646573"
            + "6b746f702e444275732e4572726f722e53657276696365556e6b6e6f776e0000000000000005017500020000000801"
            + "6700017300001b000000546865206e616d65206973206e6f74206163746976617461626c6500";

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    @Nested
    @DisplayName("encode")
    class Encode {

        @Test
        @DisplayName("Hello without body")
        void hello() {
            var message = DbusMessage.methodCall(1, "org.freedesktop.DBus", "/org/freedesktop/DBus",
                    "org.freedesktop.DBus", "Hello", "", List.of());

            assertArrayEquals(hex(HELLO), message.encode());
        }

        @Test
        @DisplayName("OpenSession with a string and a variant")
        void openSession() {
            var message = DbusMessage.methodCall(2, "org.freedesktop.secrets", "/org/freedesktop/secrets",
                    "org.freedesktop.Secret.Service", "OpenSession", "sv", List.of("plain", new Variant("s", "")));

            assertArrayEquals(hex(OPEN_SESSION), message.encode());
        }

        @Test
        @DisplayName("SearchItems with a dictionary a{ss}")
        void searchItems() {
            var attributes = new LinkedHashMap<String, String>();
            attributes.put("service", "de.planmarshall.mcp");
            attributes.put("credential_key", "github");
            var message = DbusMessage.methodCall(3, "org.freedesktop.secrets", "/org/freedesktop/secrets",
                    "org.freedesktop.Secret.Service", "SearchItems", "a{ss}", List.of(attributes));

            assertArrayEquals(hex(SEARCH), message.encode());
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        @DisplayName("a method return with reply serial, destination, sender and body")
        void helloReply() {
            var bytes = hex(HELLO_REPLY);

            var message = DbusMessage.decode(bytes);

            assertEquals(bytes.length, DbusMessage.totalLength(bytes));
            assertEquals(DbusMessage.METHOD_RETURN, message.type());
            assertEquals(DbusMessage.NO_REPLY_EXPECTED, message.flags());
            assertEquals(7, message.serial());
            assertEquals(1, message.replySerial());
            assertEquals(":1.42", message.destination());
            assertEquals("org.freedesktop.DBus", message.sender());
            assertEquals(List.of(":1.42"), message.body());
            assertNull(message.member());
        }

        @Test
        @DisplayName("a{o(oayays)} with an empty and a filled byte array")
        void secretsReply() {
            var message = DbusMessage.decode(hex(SECRETS_REPLY));

            var secrets = (Map<?, ?>) message.body().getFirst();
            var secret = (List<?>) secrets.get("/org/freedesktop/secrets/collection/login/7");
            assertEquals("/org/freedesktop/secrets/session/s1", secret.get(0));
            assertArrayEquals(new byte[0], (byte[]) secret.get(1));
            assertArrayEquals("s3cr3t".getBytes(), (byte[]) secret.get(2));
            assertEquals("text/plain", secret.get(3));
            assertEquals(4, message.replySerial());
        }

        @Test
        @DisplayName("an error with its name and message")
        void errorReply() {
            var message = DbusMessage.decode(hex(ERROR_REPLY));

            assertEquals(DbusMessage.ERROR, message.type());
            assertEquals("org.freedesktop.DBus.Error.ServiceUnknown", message.errorName());
            assertEquals(2, message.replySerial());
            assertEquals(List.of("The name is not activatable"), message.body());
        }

        @Test
        @DisplayName("refuses an unknown byte-order marker")
        void unknownOrder() {
            var bytes = hex(HELLO_REPLY);
            bytes[0] = 'x';

            assertThrows(DbusException.class, () -> DbusMessage.decode(bytes));
        }

        @Test
        @DisplayName("refuses a truncated body")
        void truncated() {
            var bytes = hex(SECRETS_REPLY);
            var truncated = Arrays.copyOf(bytes, bytes.length - 8);

            assertThrows(DbusException.class, () -> DbusMessage.decode(truncated));
        }
    }

    @Nested
    @DisplayName("values")
    class Values {

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource({
                "y, 07, 7",
                "b, 01000000, true",
                "n, feff, -2",
                "q, feff, 65534",
                "i, feffffff, -2",
                "u, feffffff, 4294967294",
                "x, feffffffffffffff, -2",
                "t, 0100000000000080, -9223372036854775807",
                "g, 02617300, as"})
        @DisplayName("basic types decode little-endian")
        void basic(String type, String bytes, String expected) {
            var value = new DbusReader(hex(bytes), ByteOrder.LITTLE_ENDIAN).read(type).getFirst();

            assertEquals(expected, String.valueOf(value));
        }

        @Test
        @DisplayName("round trip of y n q x t b v ao a{sv} (sayb) with alignment")
        void roundTrip() {
            var dict = new LinkedHashMap<String, Variant>();
            dict.put("Label", new Variant("s", "x"));
            dict.put("Attributes", new Variant("a{ss}", Map.of("k", "v")));
            var values = List.<Object>of((byte) 1, (short) -3, 65_000, -5L, 6L, true, new Variant("u", 9L),
                    List.of("/a", "/b"), dict, List.of("s", new byte[]{1, 2}, false));
            var signature = "ynqxtbvaoa{sv}(sayb)";

            var bytes = new DbusWriter().write(signature, values).toByteArray();
            var read = new DbusReader(bytes, ByteOrder.LITTLE_ENDIAN).read(signature);

            assertEquals((byte) 1, read.get(0));
            assertEquals((short) -3, read.get(1));
            assertEquals(65_000, read.get(2));
            assertEquals(-5L, read.get(3));
            assertEquals(6L, read.get(4));
            assertEquals(true, read.get(5));
            assertEquals(new Variant("u", 9L), read.get(6));
            assertEquals(List.of("/a", "/b"), read.get(7));
            var readDict = (Map<?, ?>) read.get(8);
            assertEquals(new Variant("s", "x"), readDict.get("Label"));
            assertEquals(Map.of("k", "v"), ((Variant) readDict.get("Attributes")).value());
            var struct = (List<?>) read.get(9);
            assertEquals("s", struct.get(0));
            assertArrayEquals(new byte[]{1, 2}, (byte[]) struct.get(1));
            assertEquals(false, struct.get(2));
        }

        @Test
        @DisplayName("an int64 after a byte is aligned to 8")
        void alignment() {
            var bytes = new DbusWriter().write("yx", List.of(1, 2L)).toByteArray();

            assertArrayEquals(hex("01000000000000000200000000000000"), bytes);
        }

        @Test
        @DisplayName("big-endian data decodes as declared")
        void bigEndian() {
            var value = new DbusReader(hex("00000003616263000000"), ByteOrder.BIG_ENDIAN).read("s");

            assertEquals(List.of("abc"), value);
        }

        @Test
        @DisplayName("refuses a value count that does not match the signature")
        void countMismatch() {
            var writer = new DbusWriter();
            var values = List.<Object>of("a");

            assertThrows(DbusException.class, () -> writer.write("ss", values));
        }

        @Test
        @DisplayName("refuses an unsupported type")
        void unsupported() {
            var writer = new DbusWriter();
            var values = List.<Object>of(1.0);
            var reader = new DbusReader(new byte[8], ByteOrder.LITTLE_ENDIAN);

            assertThrows(DbusException.class, () -> writer.write("d", values));
            assertThrows(DbusException.class, () -> reader.read("d"));
        }

        @Test
        @DisplayName("refuses a string without NUL and an array beyond the data")
        void malformed() {
            var noNul = new DbusReader(hex("0100000061ff"), ByteOrder.LITTLE_ENDIAN);
            var longArray = new DbusReader(hex("ff000000"), ByteOrder.LITTLE_ENDIAN);

            assertThrows(DbusException.class, () -> noNul.read("s"));
            assertThrows(DbusException.class, () -> longArray.read("ay"));
        }

        @Test
        @DisplayName("splits signatures and refuses unbalanced ones")
        void signatures() {
            assertEquals(List.of("s", "a{sv}", "(oayays)", "aao"), Signatures.split("sa{sv}(oayays)aao"));
            assertThrows(DbusException.class, () -> Signatures.split("(oay"));
            assertThrows(DbusException.class, () -> Signatures.split("a"));
        }
    }
}
