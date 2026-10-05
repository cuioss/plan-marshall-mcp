/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * The minimal JSON tree of {@code pm-api} on the {@code jackson-core} streaming API: an object is a
 * {@code Map<String, Object>} in source order, an array a {@code List<Object>}, a string a
 * {@link String}, a number a {@link JsonNumber}, a literal a {@link Boolean} or {@code null}.
 * Writing produces compact JSON on one line.
 */
@UtilityClass
public class JsonTree {

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final JsonFactory ASCII_FACTORY = JsonFactory.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    /**
     * Parses one JSON value that must span the whole input.
     *
     * @param json the JSON text
     * @return the tree
     * @throws IOException on malformed JSON or trailing content
     */
    public static Object parse(String json) throws IOException {
        try (var parser = FACTORY.createParser(json)) {
            return parseWhole(parser);
        }
    }

    /**
     * Parses one JSON value that must span the whole input.
     *
     * @param json the UTF-8 JSON bytes
     * @return the tree
     * @throws IOException on malformed JSON or trailing content
     */
    public static Object parse(byte[] json) throws IOException {
        try (var parser = FACTORY.createParser(json)) {
            return parseWhole(parser);
        }
    }

    private static Object parseWhole(JsonParser parser) throws IOException {
        if (parser.nextToken() == null) {
            throw new JsonFormatException("Empty JSON input");
        }
        var value = read(parser);
        if (parser.nextToken() != null) {
            throw new JsonFormatException("Trailing content after the JSON value");
        }
        return value;
    }

    private static Object read(JsonParser parser) throws IOException {
        var token = parser.currentToken();
        return switch (token) {
            case START_OBJECT -> {
                var map = new LinkedHashMap<String, Object>();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    var name = parser.currentName();
                    parser.nextToken();
                    map.put(name, read(parser));
                }
                yield map;
            }
            case START_ARRAY -> {
                var list = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    list.add(read(parser));
                }
                yield list;
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new JsonNumber(parser.getText());
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new JsonFormatException("Unexpected JSON token " + token);
        };
    }

    /**
     * Writes a tree as compact JSON.
     *
     * @param value the tree
     * @return the JSON text, without line breaks
     */
    public static String write(Object value) {
        return write(FACTORY, value);
    }

    /**
     * Writes a tree as compact JSON with every non-ASCII character escaped, fit for an HTTP header.
     *
     * @param value the tree
     * @return the ASCII JSON text
     */
    public static String writeAscii(Object value) {
        return write(ASCII_FACTORY, value);
    }

    /**
     * Writes a tree as compact UTF-8 JSON.
     *
     * @param value the tree
     * @return the JSON bytes
     */
    public static byte[] writeBytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    private static String write(JsonFactory factory, Object value) {
        var out = new StringWriter();
        try (var generator = factory.createGenerator(out)) {
            write(generator, value);
        } catch (IOException e) {
            // a StringWriter does not fail
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static void write(JsonGenerator generator, Object value) throws IOException {
        switch (value) {
            case null -> generator.writeNull();
            case Map<?, ?> map -> {
                generator.writeStartObject();
                for (var entry : map.entrySet()) {
                    generator.writeFieldName(String.valueOf(entry.getKey()));
                    write(generator, entry.getValue());
                }
                generator.writeEndObject();
            }
            case List<?> list -> {
                generator.writeStartArray();
                for (var element : list) {
                    write(generator, element);
                }
                generator.writeEndArray();
            }
            case String text -> generator.writeString(text);
            case JsonNumber number -> generator.writeNumber(number.text());
            case Boolean flag -> generator.writeBoolean(flag);
            case Integer number -> generator.writeNumber(number);
            case Long number -> generator.writeNumber(number);
            case Double number -> generator.writeNumber(number);
            default -> throw new IllegalArgumentException("Not a JSON tree value: " + value.getClass().getName());
        }
    }

    /**
     * @param tree a tree
     * @return the tree as object, or {@code null} if it is none
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object tree) {
        return tree instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /**
     * @param tree a tree
     * @return the tree as array, or {@code null} if it is none
     */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object tree) {
        return tree instanceof List<?> list ? (List<Object>) list : null;
    }

    /**
     * @param object an object, may be {@code null}
     * @param name   the member name
     * @return the member as object, or {@code null}
     */
    public static Map<String, Object> object(Map<String, Object> object, String name) {
        return object == null ? null : asObject(object.get(name));
    }

    /**
     * @param object an object, may be {@code null}
     * @param name   the member name
     * @return the member as string, or {@code null}
     */
    public static String string(Map<String, Object> object, String name) {
        return object != null && object.get(name) instanceof String text ? text : null;
    }
}
