/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;


import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Stage 1 argument validation of a tool call: plain Java over the closed flat schema, no JSON Schema library
 * (plan-marshall-documentation: doc/specification/mcp-tools/01-core-workflow-tools.adoc, Two-Stage Schema Validation). It understands
 * exactly the keyword subset the schemas may use: {@code properties}, {@code required},
 * {@code additionalProperties: false}, and per property {@code type}, {@code enum}, {@code pattern},
 * {@code minimum}/{@code maximum} and {@code minLength}/{@code maxLength}.
 *
 * @param schema the tool's {@code inputSchema}
 * @since 0.1
 */
public record FlatSchemaValidator(JsonObject schema) {

    /**
     * A violated rule, answered as {@code invalid_arguments}.
     *
     * @param parameter the offending parameter
     * @param rule      the violated rule
     */
    public record Violation(String parameter, String rule) {
    }

    /**
     * @param arguments the call's arguments
     * @return the first violation, or empty when the arguments conform
     */
    public Optional<Violation> validate(Map<String, Object> arguments) {
        var properties = schema.getJsonObject("properties", new JsonObject());
        for (var name : arguments.keySet()) {
            if (!properties.containsKey(name)) {
                return Optional.of(new Violation(name, "additional_property"));
            }
        }
        for (var required : schema.getJsonArray("required", new JsonArray())) {
            if (arguments.get(String.valueOf(required)) == null) {
                return Optional.of(new Violation(String.valueOf(required), "required"));
            }
        }
        for (var entry : arguments.entrySet()) {
            var violated = check(properties.getJsonObject(entry.getKey()), entry.getValue());
            if (violated.isPresent()) {
                return Optional.of(new Violation(entry.getKey(), violated.get()));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> check(JsonObject property, Object value) {
        if (value == null) {
            return Optional.of("type");
        }
        var type = property.getString("type");
        var typed = switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> isInteger(value);
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof JsonObject || value instanceof Map;
            case "array" -> value instanceof JsonArray || value instanceof List;
            default -> false;
        };
        if (!typed) {
            return Optional.of("type");
        }
        var values = property.getJsonArray("enum");
        if (values != null && !values.contains(value)) {
            return Optional.of("enum");
        }
        if (value instanceof String text) {
            return checkString(property, text);
        }
        if (value instanceof Number number) {
            var minimum = property.getNumber("minimum");
            if (minimum != null && number.doubleValue() < minimum.doubleValue()) {
                return Optional.of("minimum");
            }
            var maximum = property.getNumber("maximum");
            if (maximum != null && number.doubleValue() > maximum.doubleValue()) {
                return Optional.of("maximum");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> checkString(JsonObject property, String text) {
        var length = text.codePointCount(0, text.length());
        var minLength = property.getInteger("minLength");
        if (minLength != null && length < minLength) {
            return Optional.of("minLength");
        }
        var maxLength = property.getInteger("maxLength");
        if (maxLength != null && length > maxLength) {
            return Optional.of("maxLength");
        }
        var pattern = property.getString("pattern");
        if (pattern != null && !Pattern.compile(pattern).matcher(text).find()) {
            return Optional.of("pattern");
        }
        return Optional.empty();
    }

    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long
                || value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                && !Double.isInfinite(number.doubleValue());
    }
}
