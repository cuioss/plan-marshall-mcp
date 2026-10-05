/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.verify.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;


import de.cuioss.pm.mcp.server.lsp.FixtureLanguageServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Spike endpoint /api/v1/spike/lsp (resource logic)")
class LspSpikeResourceTest {

    private final LspSpikeResource resource = new LspSpikeResource();

    @Test
    @DisplayName("answers the fixture server's definition with timings")
    void definition(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("A.java"), "class A {}\n");

        var response = resource.definition(new LspSpikeResource.Request(FixtureLanguageServer.command("location"),
                file.toString(), 0, 6, "java"));

        assertEquals(200, response.getStatus());
        var body = (Map<?, ?>) response.getEntity();
        var location = (Map<?, ?>) ((List<?>) body.get("locations")).getFirst();
        assertEquals(3, location.get("start_line"));
        assertEquals("utf-32", body.get("position_encoding"));
    }

    @Test
    @DisplayName("reports failures of the language server and invalid requests")
    void failures(@TempDir Path dir) {
        var failed = resource.definition(new LspSpikeResource.Request(List.of(dir.resolve("none").toString()),
                dir.resolve("A.java").toString(), 0, 0, null));
        var invalid = resource.definition(new LspSpikeResource.Request(List.of(), "x", 0, 0, null));

        assertEquals(502, failed.getStatus());
        assertEquals("START_FAILED", ((Map<?, ?>) failed.getEntity()).get("error"));
        assertEquals(400, invalid.getStatus());
    }
}
