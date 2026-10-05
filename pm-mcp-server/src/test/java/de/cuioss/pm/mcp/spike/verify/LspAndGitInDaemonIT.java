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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.pm.mcp.server.lsp.FixtureLanguageServer;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate 2 and the JGit native path: the packaged daemon (JVM jar, or the native binary with
 * {@code -Pnative}) runs the product LSP client against the fixture language server and the JGit
 * scenario, and records the figures in {@code target/verification-results/}.
 */
@QuarkusIntegrationTest
@DisplayName("LSP4J and JGit inside the packaged daemon")
class LspAndGitInDaemonIT {

    private static final List<String> GIT_STEPS = List.of("init", "commit", "worktree-sha", "log", "worktree-add",
            "worktree-commit", "worktree-list", "worktree-remove", "push", "fetch");

    /** The artifact type Quarkus built and the integration test launched ({@code native} or {@code jvm}). */
    private static String packaging() {
        try {
            String properties = Files.readString(Path.of("target", "quarkus-artifact.properties"));
            return properties.contains("type=native") ? "native" : "jvm";
        } catch (IOException e) {
            return "unknown";
        }
    }

    @Test
    @DisplayName("answers textDocument/definition through LSP4J (gate 2)")
    void lsp(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Main.java"), "class Main {}\n");
        var request = new LinkedHashMap<String, Object>();
        request.put("command", FixtureLanguageServer.command("location"));
        request.put("file", file.toString());
        request.put("line", 0);
        request.put("character", 6);
        request.put("languageId", "java");

        JsonPath answer = given().contentType("application/json").body(request)
                .when().post("/api/v1/spike/lsp")
                .then().extract().jsonPath();

        boolean pass = "3".equals(answer.getString("locations[0].start_line"))
                && "utf-32".equals(answer.getString("position_encoding"));
        var values = new LinkedHashMap<String, Object>();
        values.put("packaging", packaging());
        values.put("initialize_ms", String.valueOf(answer.getString("initialize_ms")));
        values.put("definition_ms", String.valueOf(answer.getString("definition_ms")));
        values.put("error", String.valueOf(answer.getString("error")));
        VerificationResult.write("gate2-lsp4j-" + packaging(), values, pass);
        assertEquals("3", answer.getString("locations[0].start_line"), answer.prettify());
        assertEquals("utf-32", answer.getString("position_encoding"));
    }

    @Test
    @DisplayName("runs the JGit native-variant scenario")
    void git(@TempDir Path dir) {
        JsonPath answer = given().contentType("application/json")
                .body(Map.of("op", "scenario", "repo", dir.resolve("repo").toString()))
                .when().post("/api/v1/spike/git")
                .then().statusCode(200).extract().jsonPath();

        var values = new LinkedHashMap<String, Object>();
        values.put("packaging", packaging());
        values.put("elapsed_ms", answer.getLong("elapsed_ms"));
        boolean pass = true;
        for (String step : GIT_STEPS) {
            String outcome = answer.getString("steps.'" + step + "'.outcome");
            values.put(step, outcome);
            pass &= "OK".equals(outcome);
        }
        String hook = answer.getString("steps.'commit-with-hook'.outcome");
        values.put("commit-with-hook", hook);
        pass &= "CAPABILITY_MISSING".equals(hook);
        VerificationResult.write("jgit-" + packaging(), values, pass);
        assertTrue(pass, answer.prettify());
    }
}
