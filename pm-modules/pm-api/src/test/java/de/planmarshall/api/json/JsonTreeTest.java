/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("JsonTree")
class JsonTreeTest {

    @Nested
    @DisplayName("Round trip")
    class RoundTrip {

        @ParameterizedTest
        @ValueSource(strings = {"{\"a\":1,\"b\":[true,false,null],\"c\":{\"d\":\"x\"}}",
                "[1.50,-2e10,12345678901234567890123]", "\"text\"", "null", "{}"})
        @DisplayName("writes what it parsed, numbers exactly as written")
        void exact(String json) throws Exception {
            assertEquals(json, JsonTree.write(JsonTree.parse(json)));
            assertEquals(json, new String(JsonTree.writeBytes(JsonTree.parse(json.getBytes(StandardCharsets.UTF_8))),
                    StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("removes line breaks of a pretty-printed message")
        void compact() throws Exception {
            assertEquals("{\"a\":[1,2]}", JsonTree.write(JsonTree.parse("{\n  \"a\": [\n 1,\n 2]\n}")));
        }

        @Test
        @DisplayName("escapes non-ASCII characters for headers")
        void ascii() throws Exception {
            var tree = JsonTree.parse("{\"name\":\"Zoë ✓\"}");

            assertEquals("{\"name\":\"Zo\\u00EB \\u2713\"}", JsonTree.writeAscii(tree));
        }

        @Test
        @DisplayName("writes Java integers and doubles")
        void javaNumbers() {
            var map = new LinkedHashMap<String, Object>();
            map.put("i", 1);
            map.put("l", 2L);
            map.put("d", 1.5);
            map.put("n", JsonNumber.of(7));

            assertEquals("{\"i\":1,\"l\":2,\"d\":1.5,\"n\":7}", JsonTree.write(map));
        }

        @Test
        @DisplayName("refuses a non-tree value")
        void refusesForeign() {
            var list = new ArrayList<Object>();
            list.add(new Object());

            assertThrows(IllegalArgumentException.class, () -> JsonTree.write(list));
        }
    }

    @Nested
    @DisplayName("Malformed input")
    class Malformed {

        @ParameterizedTest
        @ValueSource(strings = {"", "  ", "{} {}", "{\"a\":}", "[1,"})
        @DisplayName("is refused")
        void refused(String json) {
            assertThrows(IOException.class, () -> JsonTree.parse(json));
        }
    }

    @Nested
    @DisplayName("Accessors")
    class Accessors {

        @Test
        @DisplayName("navigate objects and strings")
        void navigate() throws Exception {
            var root = JsonTree.asObject(JsonTree.parse("{\"o\":{\"s\":\"v\",\"n\":3},\"a\":[1]}"));

            assertEquals("v", JsonTree.string(JsonTree.object(root, "o"), "s"));
            assertNull(JsonTree.string(JsonTree.object(root, "o"), "n"));
            assertNull(JsonTree.object(root, "a"));
            assertNull(JsonTree.object(null, "a"));
            assertNull(JsonTree.string(null, "a"));
            assertEquals(List.of(new JsonNumber("1")), JsonTree.asArray(root.get("a")));
            assertNull(JsonTree.asArray(root));
            assertNull(JsonTree.asObject("x"));
        }

        @Test
        @DisplayName("JsonNumber exposes its value")
        void number() {
            assertEquals(42L, new JsonNumber("42").longValue());
            assertEquals("42", JsonNumber.of(42).toString());
            assertThrows(NumberFormatException.class, () -> new JsonNumber("1.5").longValue());
            assertEquals(new JsonNumber("1"), JsonNumber.of(1));
            assertNotEquals(new JsonNumber("1"), JsonNumber.of(2));
        }
    }
}
