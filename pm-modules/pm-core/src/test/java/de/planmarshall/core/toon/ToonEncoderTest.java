/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.core.toon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.planmarshall.core.toon.ToonEncodingException.Reason;
import de.planmarshall.core.toon.ToonValue.ToonArray;
import de.planmarshall.core.toon.ToonValue.ToonNull;
import de.planmarshall.core.toon.ToonValue.ToonNumber;
import de.planmarshall.core.toon.ToonValue.ToonObject;
import de.planmarshall.core.toon.ToonValue.ToonString;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ToonEncoder")
class ToonEncoderTest {

    private static ToonObject link(String rel, String href) {
        return ToonObject.builder().add("rel", rel).add("href", href).build();
    }

    @Nested
    @DisplayName("PM-MCP representations")
    class Representations {

        @Test
        @DisplayName("renders a representation with record order, tabular links and inline arrays")
        void rendersRepresentation() {
            var value = ToonObject.builder()
                    .add("state", "execute.task")
                    .add("plan_id", "p-1")
                    .add("attempt", 2)
                    .add("blocked", false)
                    .add("summary", (String) null)
                    .add("note", Optional.empty())
                    .add("tags", ToonValue.ofStrings(List.of("a", "b c")))
                    .add("links", new ToonArray(List.of(link("next", "pm://p/1"), link("self", "pm://p/1/s"))))
                    .add("failures", new ToonArray(List.of()))
                    .add("facts", ToonObject.sorted(new LinkedHashMap<>(Map.of("zeta", ToonValue.of(1),
                            "alpha", ToonValue.of("x")))))
                    .build();

            String toon = ToonEncoder.encode(value);

            assertEquals("""
                    state: execute.task
                    plan_id: p-1
                    attempt: 2
                    blocked: false
                    tags[2]: a,b c
                    links[2]{rel,href}:
                      next,"pm://p/1"
                      self,"pm://p/1/s"
                    failures: []
                    facts:
                      alpha: x
                      zeta: 1""", toon);
        }

        @Test
        @DisplayName("keeps multi-line values with headings and quotes intact (PM-WATCH-HYP-4)")
        void multiLineValue() {
            String body = "# Heading\n\nSaid \"hi\"\r\n\tindented\\path";
            var value = ToonObject.builder().add("body", body).build();

            String toon = ToonEncoder.encode(value);

            assertEquals("body: \"# Heading\\n\\nSaid \\\"hi\\\"\\r\\n\\tindented\\\\path\"", toon);
            assertFalse(toon.contains("\n"), "a multi-line value stays on its line");
            assertEquals(body, unescape(toon.substring("body: ".length())));
        }

        @Test
        @DisplayName("produces byte-identical UTF-8 for identical input (PM-WATCH-HYP-1)")
        void deterministic() {
            var first = ToonObject.builder().add("k", "ä ö 🚀").add("n", ToonValue.of(1.5)).build();
            var second = ToonObject.builder().add("k", "ä ö 🚀").add("n", ToonValue.of(1.5)).build();

            byte[] bytes = ToonEncoder.encodeUtf8(first);

            assertArrayEquals(bytes, ToonEncoder.encodeUtf8(second));
            assertEquals("k: ä ö 🚀\nn: 1.5", new String(bytes, StandardCharsets.UTF_8));
            assertFalse(bytes.length > 0 && bytes[0] == (byte) 0xEF, "no byte-order mark");
        }

        private static String unescape(String quoted) {
            var out = new StringBuilder();
            for (int i = 1; i < quoted.length() - 1; i++) {
                char c = quoted.charAt(i);
                if (c == '\\') {
                    char next = quoted.charAt(++i);
                    out.append(switch (next) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> next;
                    });
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    @Nested
    @DisplayName("A leading U+FEFF")
    class ByteOrderMark {

        private static final String MARKED = "\uFEFFabc";

        @Test
        @DisplayName("is quoted in a root string, so that a reader cannot drop it as a byte-order mark")
        void quotesRootString() {
            var encoded = ToonEncoder.encode(ToonValue.of(MARKED));

            assertEquals("\"" + MARKED + "\"", encoded);
        }

        @Test
        @DisplayName("is left unquoted in a field value, which never starts the document")
        void leavesFieldValueUnquoted() {
            var encoded = ToonEncoder.encode(ToonObject.builder().add("k", MARKED).build());

            assertEquals("k: " + MARKED, encoded);
        }
    }

    @Nested
    @DisplayName("Numbers")
    class Numbers {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "0, 0",
                "-0.0, 0",
                "1.5000, 1.5",
                "1E+6, 1000000",
                "0.000001, 0.000001",
                "1E-7, 1e-7",
                "-1.25E-9, -1.25e-9",
                "1E+21, 1e+21",
                "123456789012345678901234, 1.23456789012345678901234e+23"
        })
        @DisplayName("renders canonical decimal and exponent forms")
        void canonical(String input, String expected) {
            assertEquals(expected, ToonEncoder.number(new BigDecimal(input)));
        }

        @Test
        @DisplayName("normalizes non-finite doubles to null")
        void nonFinite() {
            assertInstanceOf(ToonNull.class, ToonValue.of(Double.NaN));
            assertInstanceOf(ToonNull.class, ToonValue.of(Double.POSITIVE_INFINITY));
            assertEquals("0.1", ToonEncoder.encode(ToonValue.of(0.1)));
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("refuses unpaired surrogates in values and keys")
        void unpairedSurrogate() {
            var inValue = assertThrows(ToonEncodingException.class, () -> ToonEncoder.encode(new ToonString("a\uD800")));
            var inKey = assertThrows(ToonEncodingException.class,
                    () -> ToonEncoder.encode(ToonObject.builder().add("\uDC00", 1).build()));
            var reversed = assertThrows(ToonEncodingException.class,
                    () -> ToonEncoder.encode(new ToonString("\uDC00\uD800")));

            assertEquals(Reason.UNPAIRED_SURROGATE, inValue.getReason());
            assertEquals(Reason.UNPAIRED_SURROGATE, inKey.getReason());
            assertEquals(Reason.UNPAIRED_SURROGATE, reversed.getReason());
        }

        @Test
        @DisplayName("refuses duplicate keys")
        void duplicateKey() {
            var builder = ToonObject.builder().add("a", 1).add("a", 2);

            var refusal = assertThrows(ToonEncodingException.class, builder::build);

            assertEquals(Reason.DUPLICATE_KEY, refusal.getReason());
        }
    }

    @Nested
    @DisplayName("Forms")
    class Forms {

        @Test
        @DisplayName("renders a nested object of uniform objects in keyed tabular form")
        void keyedNested() {
            var value = ToonObject.builder().add("m", ToonObject.builder()
                    .add("a", ToonObject.builder().add("x", 1).add("y", "p,q").build())
                    .add("my key", ToonObject.builder().add("y", "r").add("x", 2).build()).build()).build();

            assertEquals("""
                    m[2:]{x,y}:
                      a: 1,"p,q"
                      "my key": 2,r""", ToonEncoder.encode(value));
        }

        @Test
        @DisplayName("renders a root object of uniform objects with nested field groups as keyless keyed header")
        void keyedRootWithFieldGroup() {
            var value = ToonObject.builder()
                    .add("eu", ToonObject.builder().add("name", "Europe")
                            .add("geo", ToonObject.builder().add("lat", 50).add("lon", 10).build()).build())
                    .add("us", ToonObject.builder().add("name", "America")
                            .add("geo", ToonObject.builder().add("lon", -100).add("lat", 40).build()).build())
                    .build();

            assertEquals("""
                    [2:]{name,geo{lat,lon}}:
                      eu: Europe,50,10
                      us: America,40,-100""", ToonEncoder.encode(value));
        }

        @Test
        @DisplayName("renders an empty object element and mixed elements in list form")
        void listForm() {
            var value = new ToonArray(List.of(ToonObject.builder().build(), ToonValue.of("x"),
                    new ToonArray(List.of()), ToonValue.ofStrings(List.of("a", "b")),
                    new ToonArray(List.of(link("self", "s"))),
                    ToonObject.builder().add("links", new ToonArray(List.of(link("next", "n"))))
                            .add("done", true).build()));

            assertEquals("""
                    [6]:
                      -
                      - x
                      - [0]:
                      - [2]: a,b
                      - [1]:
                        - rel: self
                          href: s
                      - links[1]{rel,href}:
                          next,n
                        done: true""", ToonEncoder.encode(value));
        }
    }

    @Nested
    @DisplayName("Value tree")
    class ValueTree {

        @Test
        @DisplayName("exposes keys, lookups and primitive classification")
        void accessors() {
            var object = ToonObject.builder().add("a", true).add("b", ToonValue.of(false))
                    .add("c", Optional.of(new ToonNumber(BigDecimal.TEN))).build();

            assertEquals(List.of("a", "b", "c"), object.keys());
            assertTrue(object.get("c").isPresent());
            assertTrue(object.get("z").isEmpty());
            assertTrue(new ToonNull().isPrimitive());
            assertFalse(object.isPrimitive());
            assertFalse(new ToonArray(List.of()).isPrimitive());
        }
    }
}
