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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Closed vocabularies of the conformance harness")
class ConformanceVocabularyTest {

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({"HELD, held", "VIOLATED, violated", "INDETERMINATE, indeterminate"})
    @DisplayName("a verdict carries its value")
    void verdicts(Verdict verdict, String wireName) {
        assertEquals(wireName, verdict.wireName());
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({"ENGINE, engine", "CHECK, check", "GUIDANCE, guidance"})
    @DisplayName("an enforcement class carries its value")
    void enforcementClasses(EnforcementClass enforcementClass, String wireName) {
        assertEquals(wireName, enforcementClass.wireName());
    }

    @Test
    @DisplayName("an attribution carries its value")
    void attributions() {
        assertEquals("isolated", Attribution.ISOLATED.wireName());
        assertEquals("realistic_context", Attribution.REALISTIC_CONTEXT.wireName());
    }

    @Test
    @DisplayName("the vocabularies are closed at three, three and two values")
    void closed() {
        assertEquals(3, Verdict.values().length);
        assertEquals(3, EnforcementClass.values().length);
        assertEquals(2, Attribution.values().length);
    }
}
