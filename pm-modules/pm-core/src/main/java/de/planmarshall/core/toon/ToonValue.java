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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * An immutable value of the JSON data model as the TOON encoder consumes it.
 * <p>
 * The tree is built explicitly (records, no reflection): PM-MCP maps its records onto it in their
 * declared component order. Java {@code null} and {@link Optional#empty()} values are omitted when a
 * field is added to an {@link ToonObject}; an explicit JSON {@code null} is {@link ToonNull}.
 *
 * @since 0.1
 */
public sealed interface ToonValue
        permits ToonValue.ToonString, ToonValue.ToonNumber, ToonValue.ToonBoolean, ToonValue.ToonNull,
        ToonValue.ToonArray, ToonValue.ToonObject {

    /**
     * @return {@code true} for strings, numbers, booleans and {@code null}
     */
    default boolean isPrimitive() {
        return !(this instanceof ToonArray || this instanceof ToonObject);
    }

    /**
     * A string value.
     *
     * @param value the string, never {@code null}
     */
    record ToonString(String value) implements ToonValue {

        /**
         * @param value the string, never {@code null}
         */
        public ToonString {
            requireValue(value);
        }
    }

    /**
     * A finite number, held exactly.
     *
     * @param value the number, never {@code null}
     */
    record ToonNumber(BigDecimal value) implements ToonValue {

        /**
         * @param value the number, never {@code null}
         */
        public ToonNumber {
            requireValue(value);
        }
    }

    /**
     * A boolean value.
     *
     * @param value the boolean
     */
    record ToonBoolean(boolean value) implements ToonValue {
    }

    /** The JSON {@code null} literal. */
    record ToonNull() implements ToonValue {
    }

    /**
     * An ordered array.
     *
     * @param elements the elements, never {@code null}
     */
    record ToonArray(List<ToonValue> elements) implements ToonValue {

        /**
         * @param elements the elements, copied
         */
        public ToonArray {
            elements = List.copyOf(elements);
        }
    }

    /**
     * An object whose fields keep their insertion order.
     *
     * @param fields the fields with unique keys, never {@code null}
     */
    record ToonObject(List<Field> fields) implements ToonValue {

        /**
         * @param fields the fields, copied; keys must be unique
         * @throws ToonEncodingException with reason {@link ToonEncodingException.Reason#DUPLICATE_KEY}
         *                               if two fields share a key
         */
        public ToonObject {
            fields = List.copyOf(fields);
            var seen = new HashSet<String>();
            for (Field field : fields) {
                if (!seen.add(field.key())) {
                    throw new ToonEncodingException(ToonEncodingException.Reason.DUPLICATE_KEY,
                            "duplicate key '" + field.key() + "'");
                }
            }
        }

        /**
         * Creates an object from a map with its keys in ASCII lexicographical order
         * ({@link String#compareTo(String)}), omitting {@code null} values.
         *
         * @param entries the entries
         * @return the object
         */
        public static ToonObject sorted(Map<String, ? extends ToonValue> entries) {
            var builder = builder();
            new TreeMap<>(entries).forEach(builder::add);
            return builder.build();
        }

        /**
         * @return a builder that keeps the insertion order
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * @return the keys in field order
         */
        public List<String> keys() {
            return fields.stream().map(Field::key).toList();
        }

        /**
         * @param key the key
         * @return the value of the field, if present
         */
        public Optional<ToonValue> get(String key) {
            return fields.stream().filter(f -> f.key().equals(key)).map(Field::value).findFirst();
        }

        /**
         * One field of an object.
         *
         * @param key   the key, never {@code null}
         * @param value the value, never {@code null}
         */
        public record Field(String key, ToonValue value) {

            /**
             * @param key   the key
             * @param value the value
             */
            public Field {
                Objects.requireNonNull(key, "key");
                requireValue(value);
            }
        }

        /** Insertion-ordered builder; {@code null} and empty optional values are omitted. */
        public static final class Builder {

            private final List<Field> fields = new ArrayList<>();

            private Builder() {
            }

            /**
             * @param key   the key
             * @param value the value; omitted when {@code null}
             * @return this builder
             */
            public Builder add(String key, ToonValue value) {
                if (value != null) {
                    fields.add(new Field(key, value));
                }
                return this;
            }

            /**
             * @param key   the key
             * @param value the string; omitted when {@code null}
             * @return this builder
             */
            public Builder add(String key, String value) {
                return add(key, value == null ? null : new ToonString(value));
            }

            /**
             * @param key   the key
             * @param value the integer
             * @return this builder
             */
            public Builder add(String key, long value) {
                return add(key, ToonValue.of(value));
            }

            /**
             * @param key   the key
             * @param value the boolean
             * @return this builder
             */
            public Builder add(String key, boolean value) {
                return add(key, new ToonBoolean(value));
            }

            /**
             * @param key   the key
             * @param value the optional value; omitted when empty
             * @return this builder
             */
            public Builder add(String key, Optional<? extends ToonValue> value) {
                value.ifPresent(v -> add(key, v));
                return this;
            }

            /**
             * @return the object
             */
            public ToonObject build() {
                return new ToonObject(fields);
            }
        }
    }

    /**
     * @param value the string
     * @return a string value
     */
    static ToonValue of(String value) {
        return new ToonString(value);
    }

    /**
     * @param value the integer
     * @return a number value
     */
    static ToonValue of(long value) {
        return new ToonNumber(BigDecimal.valueOf(value));
    }

    /**
     * Normalizes a double per the official specification § 3: {@code NaN} and infinities become
     * {@code null}.
     *
     * @param value the double
     * @return a number value, or {@link ToonNull} for a non-finite value
     */
    static ToonValue of(double value) {
        if (!Double.isFinite(value)) {
            return new ToonNull();
        }
        return new ToonNumber(new BigDecimal(Double.toString(value)));
    }

    /**
     * @param value the boolean
     * @return a boolean value
     */
    static ToonValue of(boolean value) {
        return new ToonBoolean(value);
    }

    /**
     * @param values the strings
     * @return an array of string values
     */
    static ToonArray ofStrings(List<String> values) {
        return new ToonArray(values.stream().map(ToonValue::of).toList());
    }

    private static <T> T requireValue(T value) {
        return Objects.requireNonNull(value, "value");
    }
}
