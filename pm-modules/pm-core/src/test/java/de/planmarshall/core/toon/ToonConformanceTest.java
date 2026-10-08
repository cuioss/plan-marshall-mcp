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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the vendored encode fixtures of the pinned official TOON specification (gate 12).
 * <p>
 * Every fixture passes byte-exact, unless it is listed in {@code skipped.txt} because it needs an
 * encoder option PM-MCP does not offer (a delimiter other than comma, an indentation other than two
 * spaces); a listed fixture must carry such an option, so a skip never hides a wrong encoding.
 */
@DisplayName("TOON conformance fixtures (encode)")
class ToonConformanceTest {

    private static final String BASE = "/toon-fixtures/" + ToonEncoder.SPEC_RELEASE.substring(1) + "/";
    private static final List<String> FILES = List.of("primitives", "objects", "objects-keyed", "arrays-primitive",
            "arrays-tabular", "arrays-nested", "arrays-objects", "delimiters", "whitespace");
    private static final JsonFactory JSON = new JsonFactory();
    /** Options equal to the canonical encoder options (comma delimiter, indent 2); PM-MCP offers no others. */
    private static final Set<String> CANONICAL_OPTIONS = Set.of("{}", "{\"delimiter\":\",\"}");

    record Fixture(String id, String name, ToonValue input, String expected, String options, String skipReason) {

        @Override
        public String toString() {
            return id + " " + name;
        }
    }

    static Stream<Arguments> fixtures() {
        Map<String, String> skipped = skipList();
        var all = new ArrayList<Arguments>();
        for (String file : FILES) {
            all.addAll(readFile(file, skipped).stream().map(Arguments::of).toList());
        }
        return all.stream();
    }

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger OPTION_SKIPPED = new AtomicInteger();

    /** Records the gate 12 figures in {@code target/verification-results/gate12-toon-conformance.json}. */
    @AfterAll
    static void recordVerificationResult() throws IOException {
        Path file = Path.of("target", "verification-results", "gate12-toon-conformance.json");
        Files.createDirectories(file.getParent());
        int total = PASSED.get() + OPTION_SKIPPED.get();
        Files.writeString(file, """
                {
                  "item": "gate12-toon-conformance",
                  "os": "%s",
                  "arch": "%s",
                  "values": {
                    "spec_version": "%s",
                    "spec_release": "%s",
                    "spec_commit": "a6b801a3326980ab2cb615b0ffe44457e091f7b6",
                    "encode_fixtures": %d,
                    "passed": %d,
                    "skipped_encoder_option": %d
                  },
                  "pass": %s
                }
                """.formatted(System.getProperty("os.name"), System.getProperty("os.arch"), ToonEncoder.SPEC_VERSION,
                ToonEncoder.SPEC_RELEASE, total, PASSED.get(), OPTION_SKIPPED.get(),
                PASSED.get() > 0 && PASSED.get() + OPTION_SKIPPED.get() == total), StandardCharsets.UTF_8);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("encodes the fixture byte-exact unless it needs an unoffered option")
    void conforms(Fixture fixture) {
        if (fixture.skipReason() == null) {
            assertTrue(CANONICAL_OPTIONS.contains(fixture.options()), "fixture with options must be listed as skipped");
            assertEquals(fixture.expected(), ToonEncoder.encode(fixture.input()));
            PASSED.incrementAndGet();
            return;
        }
        assertTrue(fixture.skipReason().startsWith("option"), () -> "only unoffered options are skipped: " + fixture);
        assertFalse(CANONICAL_OPTIONS.contains(fixture.options()), () -> "skipped fixture uses canonical options: " + fixture);
        OPTION_SKIPPED.incrementAndGet();
        Assumptions.abort("needs an encoder option PM-MCP does not offer: " + fixture.skipReason());
    }

    @Test
    @DisplayName("every skip-list entry names an existing fixture")
    void skipListIsCurrent() {
        Map<String, String> skipped = skipList();
        long known = FILES.stream().flatMap(f -> readFile(f, Map.of()).stream()).filter(f -> skipped.containsKey(f.id()))
                .count();
        assertEquals(skipped.size(), known);
    }

    private static Map<String, String> skipList() {
        var skipped = new HashMap<String, String>();
        for (String line : resource("skipped.txt").split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                int space = line.indexOf(' ');
                skipped.put(line.substring(0, space), line.substring(space + 1).trim());
            }
        }
        return skipped;
    }

    private static List<Fixture> readFile(String file, Map<String, String> skipped) {
        var fixtures = new ArrayList<Fixture>();
        try (JsonParser parser = JSON.createParser(resource("encode/" + file + ".json"))) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("tests".equals(field)) {
                    int index = 0;
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        String id = file + "#" + index++;
                        fixtures.add(readTest(parser, id, skipped.get(id)));
                    }
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return fixtures;
    }

    private static Fixture readTest(JsonParser parser, String id, String skipReason) throws IOException {
        String name = null;
        ToonValue input = null;
        String expected = null;
        String options = "{}";
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "name" -> name = parser.getText();
                case "input" -> input = JsonTree.read(parser);
                case "expected" -> expected = parser.getText();
                case "options" -> options = JsonTree.compact(JsonTree.read(parser));
                default -> parser.skipChildren();
            }
        }
        assertNotNull(input, id);
        return new Fixture(id, name, input, expected, options, skipReason);
    }

    private static String resource(String name) {
        try (InputStream in = ToonConformanceTest.class.getResourceAsStream(BASE + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
