/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.pm.mcp.server.lsp.FixtureLanguageServer;
import de.cuioss.pm.mcp.server.test.DaemonProcess;
import de.cuioss.pm.mcp.server.test.TestBases;
import de.cuioss.pm.mcp.server.test.TestRuntime;
import de.cuioss.pm.mcp.server.test.UdsHttp;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate 2 and the JGit native path: the packaged daemon (JVM runner, or the native binary with {@code -Pnative})
 * runs the product LSP client against the fixture language server and the JGit scenario, reached over its Unix
 * socket with the runtime token, and records the figures in {@code target/verification-results/}.
 */
@DisplayName("LSP4J and JGit inside the packaged daemon")
class LspAndGitInDaemonIT {

    private static final List<String> GIT_STEPS = List.of("init", "commit", "worktree-sha", "log", "worktree-add",
            "worktree-commit", "worktree-list", "worktree-remove", "push", "fetch");

    private static Path base;
    private static DaemonProcess daemon;

    @BeforeAll
    static void start() throws IOException {
        base = TestBases.create("pml");
        daemon = DaemonProcess.startReady(base);
    }

    @AfterAll
    static void stop() throws IOException {
        daemon.close();
        TestBases.delete(base);
    }

    private static String packaging() {
        return DaemonProcess.isNative() ? "native" : "jvm";
    }

    private static UdsHttp.Response post(String path, JsonObject body) throws IOException {
        var headers = TestRuntime.bearer(daemon.token());
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        return UdsHttp.request(daemon.paths().socket(), "POST", path, headers, body.encode());
    }

    @Test
    @DisplayName("answers textDocument/definition through LSP4J (gate 2)")
    void lsp(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Main.java"), "class Main {}\n");
        var request = new JsonObject()
                .put("command", new JsonArray(FixtureLanguageServer.command("location")))
                .put("file", file.toString())
                .put("line", 0)
                .put("character", 6)
                .put("languageId", "java");

        var response = post("/api/v1/spike/lsp", request);

        var answer = new JsonObject(response.body());
        var locations = answer.getJsonArray("locations", new JsonArray());
        var startLine = locations.isEmpty() ? null : locations.getJsonObject(0).getValue("start_line");
        boolean pass = response.status() == 200 && Integer.valueOf(3).equals(startLine)
                && "utf-32".equals(answer.getString("position_encoding"));
        var values = new LinkedHashMap<String, Object>();
        values.put("packaging", packaging());
        values.put("status", response.status());
        values.put("initialize_ms", answer.getValue("initialize_ms"));
        values.put("definition_ms", answer.getValue("definition_ms"));
        values.put("error", answer.getString("error"));
        VerificationResult.write("gate2-lsp4j-" + packaging(), values, pass);

        assertEquals(200, response.status(), response.body());
        assertEquals(3, startLine, answer.encodePrettily());
        assertEquals("utf-32", answer.getString("position_encoding"));
    }

    @Test
    @DisplayName("runs the JGit native-variant scenario")
    void git(@TempDir Path dir) throws Exception {
        var response = post("/api/v1/spike/git",
                new JsonObject(Map.of("op", "scenario", "repo", dir.resolve("repo").toString())));

        assertEquals(200, response.status(), response.body());
        var answer = new JsonObject(response.body());
        var steps = answer.getJsonObject("steps");
        var values = new LinkedHashMap<String, Object>();
        values.put("packaging", packaging());
        values.put("elapsed_ms", answer.getLong("elapsed_ms"));
        boolean pass = true;
        for (String step : GIT_STEPS) {
            String outcome = outcome(steps, step);
            values.put(step, outcome);
            pass &= "OK".equals(outcome);
        }
        String hook = outcome(steps, "commit-with-hook");
        values.put("commit-with-hook", hook);
        pass &= "CAPABILITY_MISSING".equals(hook);
        VerificationResult.write("jgit-" + packaging(), values, pass);
        assertTrue(pass, answer.encodePrettily());
    }

    private static String outcome(JsonObject steps, String step) {
        var entry = steps.getJsonObject(step);
        return entry == null ? null : entry.getString("outcome");
    }
}
