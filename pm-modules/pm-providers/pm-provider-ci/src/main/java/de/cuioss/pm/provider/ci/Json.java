/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.ci;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.Serial;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Minimal, reflection-free JSON tree over the {@code jackson-core} streaming API for the provider
 * clients: objects are insertion-ordered {@link Map}s, arrays {@link List}s, numbers
 * {@link BigDecimal}s.
 *
 * @since 0.1
 */
@UtilityClass
public final class Json {

    private static final JsonFactory FACTORY = new JsonFactory();

    /**
     * @param json the JSON text
     * @return the tree
     * @throws JsonFormatException if the text is not JSON
     */
    public static Object parse(String json) {
        try (JsonParser parser = FACTORY.createParser(json)) {
            if (parser.nextToken() == null) {
                throw new JsonFormatException("empty document");
            }
            return read(parser);
        } catch (IOException e) {
            throw new JsonFormatException(e.getMessage());
        }
    }

    /**
     * @param json the JSON text
     * @return the tree, or {@code null} if the text is not JSON
     */
    public static Object parseOrNull(String json) {
        try {
            return parse(json);
        } catch (JsonFormatException e) {
            return null;
        }
    }

    private static Object read(JsonParser parser) throws IOException {
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                var map = new LinkedHashMap<String, Object>();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String key = parser.currentName();
                    parser.nextToken();
                    map.put(key, read(parser));
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
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * Writes a tree of maps, lists, strings, numbers, booleans and {@code null}.
     *
     * @param value the tree
     * @return compact JSON
     */
    public static String write(Object value) {
        var out = new StringWriter();
        try (JsonGenerator generator = FACTORY.createGenerator(out)) {
            write(generator, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static void write(JsonGenerator generator, Object value) throws IOException {
        switch (value) {
            case null -> generator.writeNull();
            case Map<?, ?> map -> {
                generator.writeStartObject();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    generator.writeFieldName(String.valueOf(entry.getKey()));
                    write(generator, entry.getValue());
                }
                generator.writeEndObject();
            }
            case List<?> list -> {
                generator.writeStartArray();
                for (Object element : list) {
                    write(generator, element);
                }
                generator.writeEndArray();
            }
            case Boolean bool -> generator.writeBoolean(bool);
            case Long number -> generator.writeNumber(number);
            case Integer number -> generator.writeNumber(number);
            case BigDecimal number -> generator.writeNumber(number);
            default -> generator.writeString(String.valueOf(value));
        }
    }

    /**
     * Navigates object keys.
     *
     * @param root the tree
     * @param keys the keys
     * @return the value at the path, if every step is an object holding the key
     */
    public static Optional<Object> at(Object root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return Optional.empty();
            }
            current = map.get(key);
        }
        return Optional.ofNullable(current);
    }

    /**
     * @param root the tree
     * @param keys the keys
     * @return the string at the path; numbers and booleans in their JSON form
     */
    public static Optional<String> string(Object root, String... keys) {
        return at(root, keys).map(v -> v instanceof BigDecimal d ? d.toPlainString() : String.valueOf(v));
    }

    /**
     * @param root the tree
     * @param keys the keys
     * @return the elements of the array at the path, empty when absent
     */
    public static List<Object> list(Object root, String... keys) {
        if (at(root, keys).orElse(null) instanceof List<?> list) {
            return Collections.unmodifiableList(new ArrayList<Object>(list));
        }
        return List.of();
    }

    /**
     * @param root the tree
     * @param keys the keys
     * @return the boolean at the path, {@code false} when absent
     */
    public static boolean bool(Object root, String... keys) {
        return at(root, keys).map(Boolean.TRUE::equals).orElse(false);
    }

    /** Thrown for text that is not JSON. */
    public static final class JsonFormatException extends IllegalArgumentException {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * @param message the parser message
         */
        public JsonFormatException(String message) {
            super(message);
        }
    }
}
