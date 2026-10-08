/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;


import io.quarkiverse.mcp.server.test.McpAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Gate 6: the {@code stdio} harness of {@code quarkus-mcp-server-test} ({@link McpAssured#newStdioClient()}),
 * whose builder launches an arbitrary command ({@code setCommand}, {@code setEnvironment},
 * {@code setWorkingDirectory}), drives {@code pm-mcp serve --client neutral} in front of the staged daemon:
 * initialize, tools/list with the ten core tools, and a call of {@code hello}. Writes
 * {@code target/verification-results/gate6-stdio-harness-<mode>.json}.
 */
@DisplayName("Gate 6: McpAssured stdio client against the relay")
class StdioHarnessIT {

    private static final List<String> CORE_TOOLS = List.of("pm_state", "pm_do", "pm_wait", "pm_plans", "pm_epics",
            "pm_build", "pm_lsp", "pm_skills", "pm_skill", "pm_skill_file");

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReleaseLayout.Mode.class)
    @DisplayName("initializes, lists the core tools and calls hello over the relay's stdio")
    void shouldDriveRelayWithStdioClient(ReleaseLayout.Mode mode, @TempDir Path workspace) throws Exception {
        try (var layout = ReleaseLayout.stage(mode)) {
            var start = layout.operator("runtime", "start");
            assertEquals(0, start.exit(), start.stderr() + layout.daemonLog());
            var stderr = new CopyOnWriteArrayList<String>();
            var client = McpAssured.newStdioClient()
                    .setCommand(layout.command("pm-mcp", "serve", "--client", "neutral"))
                    .setEnvironment(layout.environment())
                    .setWorkingDirectory(workspace)
                    .setStderrHandler(stderr::add)
                    .build();
            var values = new LinkedHashMap<String, Object>();
            values.put("mode", mode.label());
            var tools = new TreeSet<String>();
            var t0 = System.nanoTime();
            client.connect(init -> {
                assertEquals("plan-marshall-mcp", init.serverName());
                values.put("protocol_version", init.protocolVersion());
            });
            var t1 = System.nanoTime();
            try {
                client.when()
                        .toolsList(page -> page.tools().forEach(tool -> tools.add(tool.name())))
                        .toolsCall("hello", Map.of("name", "Gate6"), response -> {
                            assertFalse(response.isError(), response.toString());
                            assertEquals("Hello, Gate6!", response.firstContent().asText().text());
                        })
                        .thenAssertResults();
            } finally {
                client.disconnect();
            }
            var t2 = System.nanoTime();

            assertTrue(tools.containsAll(CORE_TOOLS), tools.toString());
            assertTrue(tools.contains("hello"), tools.toString());
            values.put("command", "pm-mcp serve --client neutral");
            values.put("connect_ms", (t1 - t0) / 1_000_000);
            values.put("list_and_call_ms", (t2 - t1) / 1_000_000);
            values.put("core_tools_listed", CORE_TOOLS.stream().filter(tools::contains).count());
            values.put("relay_stderr_lines", stderr.size());
            VerificationResults.write("gate6-stdio-harness-" + mode.label(), values, true);
        }
    }
}
