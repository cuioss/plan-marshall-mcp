/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;


import de.cuioss.pm.mcp.server.lsp.LspClient.LspLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("LspClient against a fixture language server")
class LspClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path workspace;

    private Path file;

    @BeforeEach
    void source() throws IOException {
        file = workspace.resolve("Main.java");
        Files.writeString(file, "class Main {\n  void a() {}\n  void b() { a(); }\n}\n");
    }

    @Test
    @DisplayName("initializes, negotiates utf-32, answers definition and shuts down")
    void definition() throws Exception {
        LspClient client = LspClient.start(FixtureLanguageServer.command("location"), workspace, TIMEOUT);
        try (client) {
            client.open(file, "java");

            List<LspLocation> locations = client.definition(file, 2, 13);

            assertEquals("utf-32", client.positionEncoding());
            assertEquals(List.of(new LspLocation(file.toUri().toString(), 3, 4, 3, 9)), locations);
        }
        assertFalse(client.isAlive(), "the server process ended after shutdown and exit");
    }

    @Test
    @DisplayName("reads location links and caches published diagnostics")
    void linksAndDiagnostics() throws Exception {
        try (LspClient client = LspClient.start(FixtureLanguageServer.command("links"), workspace, TIMEOUT)) {
            client.open(file, "java");

            List<LspLocation> locations = client.definition(file, 0, 0);

            assertEquals(List.of(new LspLocation(file.toUri().toString(), 3, 4, 3, 9)), locations);
            for (int i = 0; i < 100 && client.publishedDiagnostics(file).isEmpty(); i++) {
                Thread.sleep(50);
            }
            assertEquals("fixture diagnostic", client.publishedDiagnostics(file).getFirst().getMessage().getLeft());
            assertTrue(client.isAlive());
        }
    }

    @Test
    @DisplayName("reads a null definition answer as no location")
    void noDefinition() throws Exception {
        try (LspClient client = LspClient.start(FixtureLanguageServer.command("none"), workspace, TIMEOUT)) {
            assertTrue(client.definition(file, 0, 0).isEmpty());
        }
    }

    @Test
    @DisplayName("bounds a request that never answers")
    void timeout() throws Exception {
        try (LspClient client = LspClient.start(FixtureLanguageServer.command("hang"), workspace, Duration.ofSeconds(5))) {
            var refusal = assertThrows(LspException.class, () -> client.definition(file, 0, 0));

            assertEquals(LspException.Kind.TIMEOUT, refusal.getKind());
        }
    }

    @Test
    @DisplayName("terminates a server that ignores exit")
    void stubborn() throws Exception {
        LspClient client = LspClient.start(FixtureLanguageServer.command("stubborn"), workspace, Duration.ofSeconds(2));

        client.close();

        assertFalse(client.isAlive());
    }

    @Test
    @DisplayName("reports a server that cannot start or dies before initialize")
    void startFailures() throws Exception {
        var missing = assertThrows(LspException.class,
                () -> LspClient.start(List.of(workspace.resolve("no-such-server").toString()), workspace, TIMEOUT));
        var dies = assertThrows(LspException.class,
                () -> LspClient.start(FixtureLanguageServer.command("die"), workspace, Duration.ofSeconds(5)));
        LspException unreadable;
        try (LspClient client = LspClient.start(FixtureLanguageServer.command("location"), workspace, TIMEOUT)) {
            unreadable = assertThrows(LspException.class, () -> client.open(workspace.resolve("missing.java"), "java"));
        }

        assertEquals(LspException.Kind.START_FAILED, missing.getKind());
        assertEquals(LspException.Kind.START_FAILED, dies.getKind());
        assertEquals(LspException.Kind.REQUEST_FAILED, unreadable.getKind());
    }
}
