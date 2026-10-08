/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.relay;

import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

import de.planmarshall.api.json.JsonTree;

/**
 * Relay rule 5, hide identity arguments: the closed identity set ({@code generation_id},
 * {@code role}, {@code worker}, {@code job_token}) is removed from the {@code inputSchema} of every
 * tool of a {@code tools/list} result and from the arguments of a host's {@code tools/call}, so a
 * model never sees, passes, or invents an identity. Scope arguments such as {@code plan_id} are no
 * identity and pass unchanged.
 */
@UtilityClass
class IdentityArguments {

    /** The closed identity set. */
    static final Set<String> IDENTITY = Set.of("generation_id", "role", "worker", "job_token");

    /**
     * Drops identity arguments from {@code tools/call} parameters.
     *
     * @param params the request parameters, may be {@code null}
     * @return {@code true} if an argument was removed
     */
    static boolean stripCall(Map<String, Object> params) {
        var arguments = JsonTree.object(params, "arguments");
        return arguments != null && arguments.keySet().removeIf(IDENTITY::contains);
    }

    /**
     * Removes identity properties from every tool schema of a {@code tools/list} result.
     *
     * @param result the result object, may be {@code null}
     * @return {@code true} if a property was removed
     */
    static boolean stripToolsList(Map<String, Object> result) {
        var tools = result == null ? null : JsonTree.asArray(result.get("tools"));
        if (tools == null) {
            return false;
        }
        var changed = false;
        for (var tool : tools) {
            var schema = JsonTree.object(JsonTree.asObject(tool), "inputSchema");
            var properties = JsonTree.object(schema, "properties");
            if (properties != null && properties.keySet().removeIf(IDENTITY::contains)) {
                changed = true;
            }
            var required = schema == null ? null : JsonTree.asArray(schema.get("required"));
            if (required != null && required.removeIf(IDENTITY::contains)) {
                changed = true;
            }
        }
        return changed;
    }
}
