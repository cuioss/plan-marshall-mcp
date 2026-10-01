/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: FSL-1.1-ALv2
 *
 * Licensed under the Functional Source License, Version 1.1, ALv2 Future License
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE.md file at the root of this
 * repository or at https://github.com/cuioss/plan-marshall-mcp/blob/main/LICENSE.md
 */
package de.cuioss.pm.mcp.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


import de.cuioss.pm.mcp.spike.SpikeScenario.Kind;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SpikeScenario")
class SpikeScenarioTest {

    @Test
    @DisplayName("applies the defaults when only steps are given")
    void shouldApplyDefaults() {
        var scenario = SpikeScenario.parse(new JsonObject("{\"steps\":[{\"kind\":\"done\"}]}"));

        assertEquals(30_000, scenario.waitMillis());
        assertEquals(0, scenario.progressMillis());
        assertEquals(120_000, scenario.leaseMillis());
        assertEquals(2, scenario.slots());
        assertEquals(-1, scenario.addToolAfterDeliveries());
        assertTrue(scenario.nextHints());
        assertEquals(Kind.DONE, scenario.steps().getFirst().kind());
    }

    @Test
    @DisplayName("expands repeated wait steps and keeps a step's own duration")
    void shouldExpandWaitSteps() {
        var scenario = SpikeScenario.parse(new JsonObject("""
                {"wait_seconds": 2, "steps": [
                  {"kind": "wait", "repeat": 3},
                  {"kind": "wait", "seconds": 0.5, "pad_bytes": 64}]}
                """));

        assertEquals(4, scenario.steps().size());
        assertEquals(2_000, scenario.steps().getFirst().millis());
        assertEquals(500, scenario.steps().getLast().millis());
        assertEquals(64, scenario.steps().getLast().padBytes());
    }

    @Test
    @DisplayName("reads a task step with its body, delay and writer flag")
    void shouldReadTaskStep() {
        var scenario = SpikeScenario.parse(new JsonObject("""
                {"steps": [
                  {"kind": "task", "id": "t1", "writer": true, "delay_seconds": 1.5, "task": {"q": "x"}},
                  {"kind": "task", "id": "t2"}]}
                """));

        var first = scenario.steps().getFirst();
        assertEquals(Kind.TASK, first.kind());
        assertEquals("t1", first.taskId());
        assertTrue(first.writer());
        assertEquals(1_500, first.millis());
        assertEquals("x", first.task().getString("q"));
        var second = scenario.steps().getLast();
        assertFalse(second.writer());
        assertTrue(second.task().isEmpty());
    }

    @Test
    @DisplayName("loads a scenario file and reports a missing one")
    void shouldLoadFile(@TempDir Path directory) throws Exception {
        var file = directory.resolve("scenario.json");
        Files.writeString(file, "{\"slots\": 1, \"steps\": [{\"kind\": \"wait\"}]}");

        var scenario = SpikeScenario.load(file);

        assertEquals(1, scenario.slots());
        assertNull(scenario.steps().getFirst().taskId());
        var missing = directory.resolve("missing.json");
        assertThrows(IOException.class, () -> SpikeScenario.load(missing));
    }
}
