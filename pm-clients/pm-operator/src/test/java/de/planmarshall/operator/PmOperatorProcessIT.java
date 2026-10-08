/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.fasterxml.jackson.core.JsonFactory;

import de.planmarshall.api.MachinePaths;
import de.planmarshall.api.testsupport.BinaryUnderTest;
import de.planmarshall.api.testsupport.CliProcess;
import de.planmarshall.api.testsupport.RuntimeFixture;
import de.planmarshall.api.testsupport.StagedDaemon;
import de.planmarshall.api.testsupport.VerificationResults;

import picocli.CommandLine;

/**
 * {@code pm-operator} as a real process (the native image when built, else the packaged JAR) against
 * the fake runtime: the start-up time to the first request (PM-TECH-3), the detached on-demand start
 * of {@code runtime start} (gate 8), and a status without a runtime that writes nothing.
 */
@DisplayName("pm-operator as a process")
@EnabledOnOs({OS.MAC, OS.LINUX})
class PmOperatorProcessIT {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final int STARTUP_RUNS = 15;
    private static final long BUDGET_MS = 50;

    private final BinaryUnderTest binary = BinaryUnderTest.locate("pm-operator", PmOperator.class,
            MachinePaths.class, CommandLine.class, JsonFactory.class);
    private RuntimeFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = RuntimeFixture.create();
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    private Map<String, String> environment() {
        var environment = new HashMap<>(fixture.environment());
        environment.put("PATH", "/usr/bin:/bin");
        return environment;
    }

    private CliProcess operator(Map<String, String> environment, String... args) throws IOException {
        return CliProcess.start(binary.with(args), environment, fixture.base().getParent());
    }

    @Test
    @DisplayName("sends the first request of status within the start budget (PM-TECH-3)")
    void startupToFirstRequest() throws Exception {
        PmOperatorTest.writeLiveRecord(fixture);
        var server = fixture.start((request, response) -> response.json(200, PmOperatorTest.STATUS));
        var samples = new ArrayList<Long>();
        for (var run = 0; run < STARTUP_RUNS; run++) {
            try (var process = operator(environment(), "status")) {
                var request = server.awaitRequest(WAIT);
                assertNotNull(request, "no request; stderr: " + process.stderr());
                samples.add((request.receivedNanos() - process.startNanos()) / 1000);
                assertEquals(0, process.awaitExit(WAIT));
            }
        }
        Collections.sort(samples);
        var median = samples.get(samples.size() / 2);
        var pass = median <= BUDGET_MS * 1000;
        var values = new LinkedHashMap<String, Object>();
        values.put("mode", binary.mode());
        values.put("runs", (long) samples.size());
        values.put("min_ms", samples.getFirst() / 1000.0);
        values.put("median_ms", median / 1000.0);
        values.put("max_ms", samples.getLast() / 1000.0);
        values.put("budget_ms", BUDGET_MS);
        VerificationResults.write("pm-tech-3-operator-first-request-" + binary.mode(), values, pass);
        if (binary.nativeImage()) {
            assertTrue(pass, "median start-up to first request " + median / 1000.0 + " ms");
        }
    }

    @Test
    @DisplayName("status without a runtime starts nothing and writes nothing")
    void statusWithoutRuntime() throws Exception {
        try (var process = operator(environment(), "status")) {
            assertEquals(0, process.awaitExit(WAIT));
            assertEquals("runtime   not running\n", new String(process.stdout(), StandardCharsets.UTF_8));
            assertTrue(Files.notExists(fixture.base()), "status created nothing below PM_MCP_BASE");
        }
    }

    @Test
    @DisplayName("runtime start starts pm-mcpd detached and reports it only after the round trip (gate 8)")
    void detachedStart() throws Exception {
        var staged = StagedDaemon.stage(fixture.base().getParent());
        var environment = environment();
        environment.putAll(staged.environment());
        try (var process = operator(environment, "runtime", "start")) {
            var started = staged.awaitStart(WAIT);
            assertNotNull(started, "pm-mcpd was not started; stderr: " + process.stderr());
            Thread.sleep(100);
            assertEquals(0, process.stdout().length, "running is reported only after the round trip");
            fixture.start((request, response) -> response.json(200, PmOperatorTest.STATUS));

            assertEquals(0, process.awaitExit(WAIT), process.stderr());
            try {
                assertEquals("runtime   running (pid 42)\n", new String(process.stdout(), StandardCharsets.UTF_8));
                assertEquals(started[0], started[1], "pm-mcpd leads its own process group (setsid)");
                assertNotEquals(ProcessHandle.current().pid(), started[1]);
                assertTrue(staged.alive(), "pm-mcpd outlives pm-operator");
                assertEquals("rwx------", PosixFilePermissions
                        .toString(Files.getPosixFilePermissions(fixture.paths().locksDir())));
                var values = new LinkedHashMap<String, Object>();
                values.put("mode", binary.mode());
                values.put("daemon_pid", started[0]);
                values.put("daemon_pgid", started[1]);
                values.put("survived_client_exit", staged.alive());
                VerificationResults.write("gate8-detached-start-pm-operator-" + binary.mode(), values, true);
            } finally {
                staged.kill();
            }
        }
    }
}
