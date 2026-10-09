/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each test compares the whole vocabulary with its expected values, so that a value added to an enumeration
 * fails here until its value is stated.
 */
@DisplayName("Closed vocabularies of the conformance harness")
class ConformanceVocabularyTest {

    @Test
    @DisplayName("the verdicts are held, violated and indeterminate")
    void verdicts() {
        assertEquals(Map.of("HELD", "held", "VIOLATED", "violated", "INDETERMINATE", "indeterminate"),
                wireNames(Verdict.values(), Verdict::wireName));
    }

    @Test
    @DisplayName("the enforcement classes are engine, check and guidance")
    void enforcementClasses() {
        assertEquals(Map.of("ENGINE", "engine", "CHECK", "check", "GUIDANCE", "guidance"),
                wireNames(EnforcementClass.values(), EnforcementClass::wireName));
    }

    @Test
    @DisplayName("the attributions are isolated and realistic_context")
    void attributions() {
        assertEquals(Map.of("ISOLATED", "isolated", "REALISTIC_CONTEXT", "realistic_context"),
                wireNames(Attribution.values(), Attribution::wireName));
    }

    private static <E extends Enum<E>> Map<String, String> wireNames(E[] values, Function<E, String> wireName) {
        return Arrays.stream(values).collect(Collectors.toMap(Enum::name, wireName));
    }
}
