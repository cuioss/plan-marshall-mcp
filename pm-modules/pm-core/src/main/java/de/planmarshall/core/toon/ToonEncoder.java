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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

import de.planmarshall.core.toon.ToonEncodingException.Reason;
import de.planmarshall.core.toon.ToonValue.ToonArray;
import de.planmarshall.core.toon.ToonValue.ToonBoolean;
import de.planmarshall.core.toon.ToonValue.ToonNull;
import de.planmarshall.core.toon.ToonValue.ToonNumber;
import de.planmarshall.core.toon.ToonValue.ToonObject;
import de.planmarshall.core.toon.ToonValue.ToonString;

/**
 * Deterministic, reflection-free encoder of the official TOON specification version
 * {@value #SPEC_VERSION} (release {@value #SPEC_RELEASE}).
 * <p>
 * The encoder implements every form of the specification and offers its canonical options only:
 * the comma document delimiter and two spaces per indentation level (§ 13), LF line separators, no
 * trailing spaces and no trailing newline (§ 12). The form of a value follows from its shape and
 * position (§ 1.4):
 * <ul>
 *   <li>objects (§ 8), including empty objects as {@code key:};</li>
 *   <li>inline primitive arrays {@code key[N]: v1,v2} and empty arrays {@code key: []} (§ 9.1);</li>
 *   <li>tabular arrays of uniform objects {@code key[N]{a,b{c,d}}:}, with nested field groups for
 *       nested-uniform columns (§ 9.3);</li>
 *   <li>list form for arrays of arrays and for mixed or non-uniform arrays, with objects as list
 *       items (§ 9.2, § 9.4, § 10);</li>
 *   <li>keyed tabular form {@code key[N:]{a,b}:} for objects of uniform objects (§ 9.5);</li>
 *   <li>primitives with canonical numbers (§ 2) and minimal quoting with the official escapes
 *       (§ 7.1, § 7.2, § 7.3).</li>
 * </ul>
 * The output depends on the value alone: no locale, platform line separator or default charset is
 * consulted, so the bytes are identical on every platform, under JVM and under native image.
 *
 * @since 0.1
 */
@UtilityClass
public final class ToonEncoder {

    /** The pinned official TOON specification version. */
    public static final String SPEC_VERSION = "4.1";

    /** The release tag of the pinned specification in {@code github.com/toon-format/spec}. */
    public static final String SPEC_RELEASE = "v4.1.3";

    private static final String INDENT = "  ";
    private static final String LIST_MARKER = "- ";
    private static final char DELIMITER = ',';
    private static final String JOIN = String.valueOf(DELIMITER);
    /**
     * U+FEFF. A root string that starts with it is quoted, so that a reader cannot take it for a
     * byte-order mark and drop it. Written as an escape: the character itself is invisible in the
     * source.
     */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private static final Pattern NUMERIC_LIKE = Pattern.compile("^[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$");
    private static final Pattern UNQUOTED_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]*$");
    private static final BigDecimal PLAIN_MIN = new BigDecimal("1e-6");
    private static final BigDecimal PLAIN_LIMIT = new BigDecimal("1e21");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * Encodes a value as a TOON document.
     *
     * @param value the root value
     * @return the document, without trailing newline
     * @throws ToonEncodingException if a string or key holds an unpaired surrogate
     */
    public static String encode(ToonValue value) {
        var lines = new ArrayList<String>();
        switch (value) {
            case ToonObject object -> encodeRootObject(object, lines);
            case ToonArray array -> encodeArray(null, array, 0, lines);
            default -> lines.add(primitive(value, true));
        }
        return String.join("\n", lines);
    }

    /**
     * Encodes a value as UTF-8 bytes without byte-order mark.
     *
     * @param value the root value
     * @return the UTF-8 bytes of {@link #encode(ToonValue)}
     */
    public static byte[] encodeUtf8(ToonValue value) {
        return encode(value).getBytes(StandardCharsets.UTF_8);
    }

    private static void encodeRootObject(ToonObject object, List<String> lines) {
        if (isKeyedEligible(object)) {
            encodeKeyed(null, object, 0, lines);
        } else {
            encodeFields(object, 0, lines);
        }
    }

    private static void encodeFields(ToonObject object, int depth, List<String> lines) {
        for (ToonObject.Field field : object.fields()) {
            encodeField(field.key(), field.value(), depth, lines);
        }
    }

    /** One object field at {@code depth} (§ 8, § 9.5). */
    private static void encodeField(String key, ToonValue value, int depth, List<String> lines) {
        String indent = INDENT.repeat(depth);
        switch (value) {
            case ToonObject object -> {
                if (isKeyedEligible(object)) {
                    encodeKeyed(key, object, depth, lines);
                } else {
                    lines.add(indent + key(key) + ":");
                    encodeFields(object, depth + 1, lines);
                }
            }
            case ToonArray array -> encodeArray(key, array, depth, lines);
            default -> lines.add(indent + key(key) + ": " + primitive(value, false));
        }
    }

    /** Keyed tabular form of § 9.5; {@code key == null} at the root. */
    private static void encodeKeyed(String key, ToonObject object, int depth, List<String> lines) {
        List<ToonValue> values = entryValues(object);
        var first = (ToonObject) values.getFirst();
        lines.add(INDENT.repeat(depth) + (key == null ? "" : key(key)) + "[" + values.size() + ":]"
                + fieldList(first, values) + ":");
        String rowIndent = INDENT.repeat(depth + 1);
        for (ToonObject.Field entry : object.fields()) {
            lines.add(rowIndent + key(entry.key()) + ": " + row(first, values, (ToonObject) entry.value()));
        }
    }

    /**
     * An array in field position ({@code key != null}) or at the root (§ 9.1, § 9.3, § 9.4).
     */
    private static void encodeArray(String key, ToonArray array, int depth, List<String> lines) {
        String indent = INDENT.repeat(depth);
        String prefix = key == null ? "" : key(key);
        List<ToonValue> elements = array.elements();
        if (elements.isEmpty()) {
            lines.add(indent + (key == null ? "[]" : prefix + ": []"));
            return;
        }
        String header = indent + prefix + "[" + elements.size() + "]";
        if (allPrimitive(elements)) {
            lines.add(header + ": " + inline(elements));
        } else if (isUniformObjects(elements)) {
            var first = (ToonObject) elements.getFirst();
            lines.add(header + fieldList(first, elements) + ":");
            String rowIndent = INDENT.repeat(depth + 1);
            for (ToonValue element : elements) {
                lines.add(rowIndent + row(first, elements, (ToonObject) element));
            }
        } else {
            lines.add(header + ":");
            encodeListItems(elements, depth + 1, lines);
        }
    }

    /** The list items of an array in list form, each at {@code depth} (§ 9.2, § 9.4, § 10). */
    private static void encodeListItems(List<ToonValue> elements, int depth, List<String> lines) {
        String indent = INDENT.repeat(depth);
        for (ToonValue element : elements) {
            switch (element) {
                case ToonArray inner -> encodeListArray(inner, depth, lines);
                case ToonObject object -> encodeListObject(object, depth, lines);
                default -> lines.add(indent + LIST_MARKER + primitive(element, false));
            }
        }
    }

    /**
     * An array as a list item: inline when primitive (an empty one as {@code - [0]:}), otherwise in
     * list form, since a keyless tabular header is valid only at the root (§ 9.2, § 9.4).
     */
    private static void encodeListArray(ToonArray array, int depth, List<String> lines) {
        List<ToonValue> elements = array.elements();
        String header = INDENT.repeat(depth) + LIST_MARKER + "[" + elements.size() + "]:";
        if (elements.isEmpty()) {
            lines.add(header);
        } else if (allPrimitive(elements)) {
            lines.add(header + " " + inline(elements));
        } else {
            lines.add(header);
            encodeListItems(elements, depth + 1, lines);
        }
    }

    /**
     * An object as a list item (§ 10): a bare hyphen when empty, otherwise its fields at
     * {@code depth + 1} with the first one carried on the hyphen line. The hyphen marker is exactly
     * one indentation unit wide, so the first field keeps depth {@code depth + 1} and its scope (nested
     * fields, list items, rows or entry rows) stays at {@code depth + 2}, the placement § 10 prescribes
     * for every kind of first field.
     */
    private static void encodeListObject(ToonObject object, int depth, List<String> lines) {
        String indent = INDENT.repeat(depth);
        if (object.fields().isEmpty()) {
            lines.add(indent + "-");
            return;
        }
        int firstLine = lines.size();
        encodeFields(object, depth + 1, lines);
        lines.set(firstLine, indent + LIST_MARKER + lines.get(firstLine).substring(indent.length() + INDENT.length()));
    }

    /**
     * The field list of a tabular or keyed header: the first object's keys in encounter order, a
     * nested-uniform column as a nested field group, applied recursively (§ 9.3).
     */
    private static String fieldList(ToonObject first, List<ToonValue> objects) {
        var entries = new ArrayList<String>(first.fields().size());
        for (String field : first.keys()) {
            List<ToonValue> column = column(objects, field);
            String name = key(field);
            entries.add(allPrimitive(column) ? name : name + fieldList((ToonObject) column.getFirst(), column));
        }
        return "{" + String.join(JOIN, entries) + "}";
    }

    /** The cells of one row: leaf values in depth-first pre-order of the field list (§ 9.3). */
    private static String row(ToonObject first, List<ToonValue> objects, ToonObject object) {
        var cells = new ArrayList<String>();
        collectCells(first, objects, object, cells);
        return String.join(JOIN, cells);
    }

    private static void collectCells(ToonObject first, List<ToonValue> objects, ToonObject object,
            List<String> cells) {
        for (String field : first.keys()) {
            List<ToonValue> column = column(objects, field);
            ToonValue value = value(object, field);
            if (allPrimitive(column)) {
                cells.add(primitive(value, false));
            } else {
                collectCells((ToonObject) column.getFirst(), column, (ToonObject) value, cells);
            }
        }
    }

    private static String inline(List<ToonValue> elements) {
        var values = new ArrayList<String>(elements.size());
        for (ToonValue element : elements) {
            values.add(primitive(element, false));
        }
        return String.join(JOIN, values);
    }

    /**
     * Tabular detection of § 9.3: non-empty objects with the same key set whose every column is
     * uniform-primitive or nested-uniform.
     */
    private static boolean isUniformObjects(List<ToonValue> values) {
        Set<String> keySet = null;
        for (ToonValue value : values) {
            if (!(value instanceof ToonObject object) || object.fields().isEmpty()) {
                return false;
            }
            var keys = new HashSet<>(object.keys());
            if (keySet == null) {
                keySet = keys;
            } else if (!keySet.equals(keys)) {
                return false;
            }
        }
        if (keySet == null) {
            return false;
        }
        for (String field : keySet) {
            List<ToonValue> column = column(values, field);
            if (!allPrimitive(column) && !isUniformObjects(column)) {
                return false;
            }
        }
        return true;
    }

    /** Keyed tabular detection of § 9.5: at least two entries whose values are uniform objects. */
    private static boolean isKeyedEligible(ToonObject object) {
        return object.fields().size() >= 2 && isUniformObjects(entryValues(object));
    }

    private static List<ToonValue> entryValues(ToonObject object) {
        return object.fields().stream().map(ToonObject.Field::value).toList();
    }

    private static boolean allPrimitive(List<ToonValue> values) {
        return values.stream().allMatch(ToonValue::isPrimitive);
    }

    private static List<ToonValue> column(List<ToonValue> objects, String field) {
        return objects.stream().map(o -> value((ToonObject) o, field)).toList();
    }

    private static ToonValue value(ToonObject object, String field) {
        return object.get(field).orElseThrow();
    }

    private static String primitive(ToonValue value, boolean root) {
        return switch (value) {
            case ToonString string -> string(string.value(), root);
            case ToonNumber number -> number(number.value());
            case ToonBoolean bool -> String.valueOf(bool.value());
            case ToonNull ignored -> "null";
            default -> throw new IllegalStateException("not a primitive: " + value);
        };
    }

    /** Canonical number form of § 2. */
    static String number(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        BigDecimal abs = stripped.abs();
        if (abs.compareTo(PLAIN_MIN) >= 0 && abs.compareTo(PLAIN_LIMIT) < 0) {
            return stripped.toPlainString();
        }
        String digits = stripped.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - stripped.scale();
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return (stripped.signum() < 0 ? "-" : "") + mantissa + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
    }

    /** Minimal quoting of § 7.2 with the comma delimiter. */
    private static String string(String value, boolean root) {
        checkScalarValues(value);
        return needsQuotes(value, root) ? quote(value) : value;
    }

    private static boolean needsQuotes(String value, boolean root) {
        if (value.isEmpty() || "true".equals(value) || "false".equals(value) || "null".equals(value)) {
            return true;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if (isPadding(first) || isPadding(last) || first == '-' || first == '#' || root && first == BYTE_ORDER_MARK) {
            return true;
        }
        if (NUMERIC_LIKE.matcher(value).matches()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == DELIMITER || ":\"\\[]{}".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(char c) {
        return c == ' ' || c == '\t';
    }

    /** Key encoding of § 7.3. */
    private static String key(String key) {
        checkScalarValues(key);
        return UNQUOTED_KEY.matcher(key).matches() ? key : quote(key);
    }

    /** Escaping of § 7.1: the named escapes, a lowercase-hex unicode escape for other controls, else literal. */
    private static String quote(String value) {
        var out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Refuses unpaired surrogates (§ 3). */
    private static void checkScalarValues(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new ToonEncodingException(Reason.UNPAIRED_SURROGATE, "at index " + i);
            }
        }
    }
}
