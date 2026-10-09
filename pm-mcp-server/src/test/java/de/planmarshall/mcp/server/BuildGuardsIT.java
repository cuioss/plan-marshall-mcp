/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Controls of the build guards of this repository (PM-IMPL-1): each fixture project below
 * {@code src/guard-controls} breaks one rule of the root POM, and its build must fail in the enforcer
 * execution of that rule.
 * <p>
 * Hazards the guards answer: {@code pm-e2e} and {@code pm-conformance} reach the product through its built
 * binaries alone. A class of {@code pm-mcp-server} on the classpath of {@code pm-e2e} lets a test pass against
 * the code of the build where the release layout would fail; any Maven dependency of {@code pm-conformance} on
 * the assembly ties the out-of-band harness to the regular build. A guard that never fails proves nothing, hence
 * these controls. The test lives in this module because its integration tests are the ones the CI job
 * {@code integration-tests} runs.
 */
@DisplayName("Build guards of the repository")
class BuildGuardsIT {

    @ParameterizedTest(name = "{0} fails in {1}")
    @CsvSource({
            "e2e-assembly-classes, binaries-only",
            "conformance-assembly, no-assembly"})
    @DisplayName("a fixture that breaks a rule fails the build")
    void fixtureFails(String fixture, String execution) throws Exception {
        var root = Path.of(System.getProperty("pm.root"));
        var pom = root.resolve("src/guard-controls").resolve(fixture).resolve("pom.xml");
        var builder = new ProcessBuilder(root.resolve("mvnw").toString(), "-B", "--no-transfer-progress", "-f",
                pom.toString(), "validate").directory(pom.getParent().toFile()).redirectErrorStream(true);

        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "the build of the fixture did not end");

        assertNotEquals(0, process.exitValue(), output);
        assertTrue(output.contains("enforce (" + execution + ")"), output);
        assertTrue(output.contains("BUILD FAILURE"), output);
    }
}
