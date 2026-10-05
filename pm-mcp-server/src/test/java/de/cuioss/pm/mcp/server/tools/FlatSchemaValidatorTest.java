/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


import de.cuioss.pm.mcp.server.test.ToolSchemaRules;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Core tool schemas and their stage-1 validation")
class FlatSchemaValidatorTest {

    private static FlatSchemaValidator validator(String tool) {
        return new FlatSchemaValidator(CoreTools.load(tool).getJsonObject("inputSchema"));
    }

    @Test
    @DisplayName("every core tool schema obeys the flat-schema constraints")
    void shouldKeepConstraints() {
        for (var name : CoreTools.NAMES) {
            var definition = CoreTools.load(name);
            assertTrue(ToolSchemaRules.violations(name, definition.getJsonObject("inputSchema")).isEmpty(),
                    () -> ToolSchemaRules.violations(name, definition.getJsonObject("inputSchema")).toString());
            assertTrue(definition.getString("description").length() > 10);
        }
    }

    @Test
    @DisplayName("accepts conforming arguments")
    void shouldAcceptValidArguments() {
        var arguments = new HashMap<String, Object>(Map.of("plan_id", "NO_PLAN", "op", "hover", "path",
                "src/Main.java", "line", 3, "character", 7));

        assertTrue(validator("pm_lsp").validate(arguments).isEmpty());
        assertTrue(validator("pm_do").validate(Map.of("link", "submit:TASK-003", "args", new JsonObject()))
                .isEmpty());
        assertTrue(validator("pm_epics").validate(Map.of("include_archived", true)).isEmpty());
        assertTrue(validator("pm_wait").validate(Map.of()).isEmpty());
    }

    static Stream<Arguments> violations() {
        return Stream.of(
                Arguments.of("pm_wait", Map.of("worker", "w1"), "worker", "additional_property"),
                Arguments.of("pm_do", Map.of("plan_id", "NO_PLAN"), "link", "required"),
                Arguments.of("pm_lsp", Map.of("plan_id", "NO_PLAN", "op", "format"), "op", "enum"),
                Arguments.of("pm_lsp", Map.of("plan_id", "NO_PLAN", "op", "hover", "line", 0), "line", "minimum"),
                Arguments.of("pm_lsp", Map.of("plan_id", "NO_PLAN", "op", "hover", "line", "3"), "line", "type"),
                Arguments.of("pm_lsp", Map.of("plan_id", "NO_PLAN", "op", "hover", "path", ""), "path",
                        "minLength"),
                Arguments.of("pm_lsp", Map.of("plan_id", "NO_PLAN", "op", "workspace_symbol", "query",
                        "q".repeat(257)), "query", "maxLength"),
                Arguments.of("pm_state", Map.of("plan_id", "Bad_Plan"), "plan_id", "pattern"),
                Arguments.of("pm_do", Map.of("link", "ack", "args", List.of()), "args", "type"),
                Arguments.of("pm_epics", Map.of("include_archived", "yes"), "include_archived", "type"),
                Arguments.of("pm_build", Map.of("plan_id", "NO_PLAN", "command", "verify", "job_id", "b-1"),
                        "job_id", "pattern"));
    }

    @ParameterizedTest(name = "{0}: {2} violates {3}")
    @MethodSource("violations")
    @DisplayName("names the offending parameter and the violated rule")
    void shouldReportViolation(String tool, Map<String, Object> arguments, String parameter, String rule) {
        var violation = validator(tool).validate(arguments);

        assertTrue(violation.isPresent());
        assertEquals(parameter, violation.get().parameter());
        assertEquals(rule, violation.get().rule());
    }

    @Test
    @DisplayName("treats an integral double as an integer and an unknown type as a mismatch")
    void shouldHandleNumberShapes() {
        assertTrue(validator("pm_lsp").validate(Map.of("plan_id", "NO_PLAN", "op", "hover", "line", 2.0))
                .isEmpty());
        var schema = new JsonObject().put("type", "object").put("properties", new JsonObject()
                .put("n", new JsonObject().put("type", "integer").put("maximum", 5))
                .put("x", new JsonObject().put("type", "array"))
                .put("y", new JsonObject().put("type", "null")));
        var flat = new FlatSchemaValidator(schema);
        assertEquals("maximum", flat.validate(Map.of("n", 6)).orElseThrow().rule());
        assertTrue(flat.validate(Map.of("x", List.of(1))).isEmpty());
        assertEquals("type", flat.validate(Map.of("y", "z")).orElseThrow().rule());
        var withNull = new HashMap<String, Object>();
        withNull.put("n", null);
        assertEquals("type", flat.validate(withNull).orElseThrow().rule());
    }
}
