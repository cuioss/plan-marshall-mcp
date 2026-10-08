/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;


import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The flat-schema constraints of doc/specification/mcp-tools/01-core-workflow-tools.adoc, Architectural
 * Constraints, item 3, as a checker over one {@code inputSchema}.
 */
public final class ToolSchemaRules {

    static final Set<String> TOP_LEVEL = Set.of("type", "properties", "required", "additionalProperties");
    static final Set<String> PROPERTY = Set.of("type", "enum", "pattern", "minimum", "maximum", "minLength",
            "maxLength", "default", "description");
    private static final Pattern LOOKAROUND = Pattern.compile("\\(\\?<?[=!]");

    private ToolSchemaRules() {
    }

    /**
     * @param tool   the tool name, for the messages
     * @param schema the input schema as listed
     * @return every violation, empty when the schema conforms
     */
    public static List<String> violations(String tool, JsonObject schema) {
        var violations = new ArrayList<String>();
        for (var key : schema.fieldNames()) {
            if (!TOP_LEVEL.contains(key)) {
                violations.add(tool + ": top-level keyword " + key);
            }
        }
        if (!"object".equals(schema.getString("type"))) {
            violations.add(tool + ": type is not object");
        }
        if (!Boolean.FALSE.equals(schema.getValue("additionalProperties"))) {
            violations.add(tool + ": additionalProperties is not false");
        }
        var properties = schema.getJsonObject("properties", new JsonObject());
        for (var name : properties.fieldNames()) {
            var property = properties.getJsonObject(name);
            for (var key : property.fieldNames()) {
                if (!PROPERTY.contains(key)) {
                    violations.add(tool + "." + name + ": keyword " + key);
                }
            }
            var pattern = property.getString("pattern");
            if (pattern != null && LOOKAROUND.matcher(pattern).find()) {
                violations.add(tool + "." + name + ": pattern with lookaround");
            }
        }
        for (var required : schema.getJsonArray("required", new JsonArray())) {
            if (!properties.containsKey(String.valueOf(required))) {
                violations.add(tool + ": required " + required + " is no property");
            }
        }
        return violations;
    }
}
