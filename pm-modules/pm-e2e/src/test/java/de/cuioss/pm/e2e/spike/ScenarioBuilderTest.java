/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Stub scenarios of the harness drivers")
class ScenarioBuilderTest {

    @Test
    @DisplayName("fault matrix: the Part A cadence of one task every ten seconds under explicit acknowledgement")
    void shouldBuildFaultMatrix() {
        var scenario = ScenarioBuilder.faultMatrix(20, 30);

        assertTrue(scenario.path("supervised").asBoolean());
        assertFalse(scenario.path("control").asBoolean());
        assertEquals("explicit", scenario.path("ack").asText());
        assertEquals(20, scenario.path("wait_seconds").asInt());
        var steps = scenario.path("steps");
        assertEquals(30, steps.size());
        assertEquals("f000-rem-01", steps.get(0).path("id").asText());
        assertEquals(5, steps.get(0).path("release_at_seconds").asInt());
        assertEquals(295, steps.get(29).path("release_at_seconds").asInt());
        assertEquals(steps.get(0).path("task"), steps.get(ScenarioBuilder.templateCount()).path("task"));
    }

    @ParameterizedTest(name = "{0} chars")
    @ValueSource(ints = {8_000, 32_000, 128_000})
    @DisplayName("sized: pads the facts of a reserved task to the requested size")
    void shouldPadFacts(int size) {
        var scenario = ScenarioBuilder.sized(20, List.of(new ScenarioBuilder.SizedTask("t1", "h1", size)));

        var step = scenario.path("steps").get(0);
        assertEquals("h1", step.path("offer_to").asText());
        assertEquals(size, step.path("task").path("facts").asText().length());
        assertTrue(step.path("task").path("options").size() > 1);
    }

    @Test
    @DisplayName("blocking wait: outside the supervised protocol, holding for the given time")
    void shouldBuildBlockingWait() {
        var scenario = ScenarioBuilder.blockingWait(3_000, 5);

        assertFalse(scenario.path("supervised").asBoolean());
        assertEquals(5, scenario.path("progress_seconds").asInt());
        assertEquals(3_000, scenario.path("steps").get(0).path("seconds").asInt());
    }
}
