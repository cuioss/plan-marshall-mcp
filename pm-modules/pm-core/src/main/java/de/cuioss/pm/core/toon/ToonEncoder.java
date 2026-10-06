/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.core.toon;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

import de.cuioss.pm.core.toon.ToonEncodingException.Reason;
import de.cuioss.pm.core.toon.ToonValue.ToonArray;
import de.cuioss.pm.core.toon.ToonValue.ToonBoolean;
import de.cuioss.pm.core.toon.ToonValue.ToonNull;
import de.cuioss.pm.core.toon.ToonValue.ToonNumber;
import de.cuioss.pm.core.toon.ToonValue.ToonObject;
import de.cuioss.pm.core.toon.ToonValue.ToonString;

/**
 * Deterministic, reflection-free TOON encoder for the subset PM-MCP emits.
 * <p>
 * Conforms to the official TOON specification version {@value #SPEC_VERSION} (release
 * {@value #SPEC_RELEASE}) with its defaults: comma document delimiter and two spaces per
 * indentation level, LF line separators, no trailing spaces and no trailing newline (§ 12).
 * The subset covers:
 * <ul>
 *   <li>objects (§ 8), including empty objects as {@code key:};</li>
 *   <li>inline primitive arrays {@code key[N]: v1,v2} and empty arrays {@code key: []} (§ 9.1);</li>
 *   <li>tabular arrays of uniform objects with primitive columns {@code key[N]{a,b}:} (§ 9.3);</li>
 *   <li>primitives with canonical numbers (§ 2) and minimal quoting with the official escapes
 *       (§ 7.1, § 7.2, § 7.3); no block literals.</li>
 * </ul>
 * The form of a value follows from its shape (§ 1.4). A shape the specification renders in another
 * form (list form, keyed tabular form, nested field groups) is refused with
 * {@link ToonEncodingException} rather than rendered in a non-conformant way.
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
    private static final char DELIMITER = ',';
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
     * @throws ToonEncodingException if the value is outside the subset or holds an unpaired surrogate
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
            throw new ToonEncodingException(Reason.KEYED_TABULAR_FORM, "root object of uniform objects");
        }
        encodeFields(object, 0, lines);
    }

    private static void encodeFields(ToonObject object, int depth, List<String> lines) {
        for (ToonObject.Field field : object.fields()) {
            encodeField(field.key(), field.value(), depth, lines);
        }
    }

    private static void encodeField(String key, ToonValue value, int depth, List<String> lines) {
        String indent = INDENT.repeat(depth);
        switch (value) {
            case ToonObject object -> {
                if (isKeyedEligible(object)) {
                    throw new ToonEncodingException(Reason.KEYED_TABULAR_FORM, "field '" + key + "'");
                }
                lines.add(indent + key(key) + ":");
                encodeFields(object, depth + 1, lines);
            }
            case ToonArray array -> encodeArray(key, array, depth, lines);
            default -> lines.add(indent + key(key) + ": " + primitive(value, false));
        }
    }

    /**
     * Encodes an array in field position ({@code key != null}) or at the root.
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
        if (elements.stream().allMatch(ToonValue::isPrimitive)) {
            var values = new ArrayList<String>(elements.size());
            for (ToonValue element : elements) {
                values.add(cell(element));
            }
            lines.add(header + ": " + String.join(String.valueOf(DELIMITER), values));
            return;
        }
        List<String> fields = tabularFields(elements, prefix);
        var names = new ArrayList<String>(fields.size());
        for (String field : fields) {
            names.add(key(field));
        }
        lines.add(header + "{" + String.join(String.valueOf(DELIMITER), names) + "}:");
        String rowIndent = INDENT.repeat(depth + 1);
        for (ToonValue element : elements) {
            var object = (ToonObject) element;
            var cells = new ArrayList<String>(fields.size());
            for (String field : fields) {
                cells.add(cell(object.get(field).orElseThrow()));
            }
            lines.add(rowIndent + String.join(String.valueOf(DELIMITER), cells));
        }
    }

    /**
     * Returns the field list of a tabular array with primitive columns, or refuses the array.
     */
    private static List<String> tabularFields(List<ToonValue> elements, String key) {
        if (!isUniformObjects(elements)) {
            throw new ToonEncodingException(Reason.LIST_FORM, "array '" + key + "'");
        }
        var first = (ToonObject) elements.getFirst();
        for (String field : first.keys()) {
            if (!column(elements, field).stream().allMatch(ToonValue::isPrimitive)) {
                throw new ToonEncodingException(Reason.NESTED_FIELD_GROUP, "array '" + key + "' column '" + field + "'");
            }
        }
        return first.keys();
    }

    /**
     * Tabular detection of § 9.3: non-empty objects with the same key set whose every column is
     * uniform-primitive or nested-uniform.
     */
    private static boolean isUniformObjects(List<ToonValue> values) {
        if (values.isEmpty()) {
            return false;
        }
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
        for (String field : keySet) {
            List<ToonValue> column = column(values, field);
            if (!column.stream().allMatch(ToonValue::isPrimitive) && !isUniformObjects(column)) {
                return false;
            }
        }
        return true;
    }

    /** Keyed tabular detection of § 9.5: at least two entries whose values are uniform objects. */
    private static boolean isKeyedEligible(ToonObject object) {
        if (object.fields().size() < 2) {
            return false;
        }
        return isUniformObjects(object.fields().stream().map(ToonObject.Field::value).toList());
    }

    private static List<ToonValue> column(List<ToonValue> objects, String field) {
        return objects.stream().map(o -> ((ToonObject) o).get(field).orElseThrow()).toList();
    }

    private static String cell(ToonValue value) {
        return primitive(value, false);
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
        if (isPadding(first) || isPadding(last) || first == '-' || first == '#' || root && first == '\uFEFF') {
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

    /** Escaping of § 7.1. */
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
