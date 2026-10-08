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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.stream.Collectors;

import de.planmarshall.core.toon.ToonValue.ToonArray;
import de.planmarshall.core.toon.ToonValue.ToonBoolean;
import de.planmarshall.core.toon.ToonValue.ToonNull;
import de.planmarshall.core.toon.ToonValue.ToonNumber;
import de.planmarshall.core.toon.ToonValue.ToonObject;
import de.planmarshall.core.toon.ToonValue.ToonString;

/** Reads JSON into the {@link ToonValue} tree with the jackson-core streaming API (test helper). */
final class JsonTree {

    private JsonTree() {
    }

    /**
     * Reads the value at the parser's current token.
     */
    static ToonValue read(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        return switch (token) {
            case START_OBJECT -> {
                var builder = ToonObject.builder();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String key = parser.currentName();
                    parser.nextToken();
                    builder.add(key, read(parser));
                }
                yield builder.build();
            }
            case START_ARRAY -> {
                var elements = new ArrayList<ToonValue>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    elements.add(read(parser));
                }
                yield new ToonArray(elements);
            }
            case VALUE_STRING -> new ToonString(parser.getText());
            case VALUE_NUMBER_INT -> new ToonNumber(new BigDecimal(parser.getBigIntegerValue()));
            case VALUE_NUMBER_FLOAT -> new ToonNumber(parser.getDecimalValue());
            case VALUE_TRUE -> new ToonBoolean(true);
            case VALUE_FALSE -> new ToonBoolean(false);
            case VALUE_NULL -> new ToonNull();
            default -> throw new IOException("unexpected token " + token);
        };
    }

    /**
     * Renders a tree as compact JSON, enough for fixture option maps.
     */
    static String compact(ToonValue value) {
        return switch (value) {
            case ToonObject object -> object.fields().stream()
                    .map(f -> quote(f.key()) + ":" + compact(f.value()))
                    .collect(Collectors.joining(",", "{", "}"));
            case ToonArray array -> array.elements().stream().map(JsonTree::compact)
                    .collect(Collectors.joining(",", "[", "]"));
            case ToonString string -> quote(string.value());
            case ToonNumber number -> number.value().toPlainString();
            case ToonBoolean bool -> String.valueOf(bool.value());
            case ToonNull ignored -> "null";
        };
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t") + "\"";
    }
}
