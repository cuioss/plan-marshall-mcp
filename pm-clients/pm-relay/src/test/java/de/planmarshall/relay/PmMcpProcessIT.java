/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.fasterxml.jackson.core.JsonFactory;

import de.planmarshall.api.MachinePaths;
import de.planmarshall.api.json.JsonTree;
import de.planmarshall.api.testsupport.BinaryUnderTest;
import de.planmarshall.api.testsupport.CliProcess;
import de.planmarshall.api.testsupport.FakeRuntimeServer;
import de.planmarshall.api.testsupport.RuntimeFixture;
import de.planmarshall.api.testsupport.StagedDaemon;
import de.planmarshall.api.testsupport.VerificationResults;

import picocli.CommandLine;

/**
 * {@code pm-mcp serve} as a real process (the native image when built, else the packaged JAR) against
 * the fake runtime on a Unix socket: stdout hygiene and newline framing, termination on stdin EOF,
 * the start-up time to the first forwarded request (PM-TECH-3: 50 ms for the native binary), and the
 * detached on-demand start (gate 8).
 */
@DisplayName("pm-mcp serve as a process")
@EnabledOnOs({OS.MAC, OS.LINUX})
class PmMcpProcessIT {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-11-25\",\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"},\"capabilities\":{}}}";
    private static final int STARTUP_RUNS = 15;
    private static final long BUDGET_MS = 50;

    private final BinaryUnderTest binary = BinaryUnderTest.locate("pm-mcp", PmMcp.class, MachinePaths.class,
            CommandLine.class, JsonFactory.class);
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

    private CliProcess relay(Map<String, String> environment) throws IOException {
        return CliProcess.start(binary.with("serve", "--client", "claude"), environment, fixture.base().getParent());
    }

    private static FakeRuntimeServer.Handler mcp() {
        return (request, response) -> {
            switch (String.valueOf(request.header("Mcp-Method"))) {
                case "initialize" -> response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\"}}");
                case "tools/list" -> {
                    try (var events = response.events(200)) {
                        events.send(null, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}");
                        events.send("message", "{\"jsonrpc\":\"2.0\",\n \"id\":2,\n \"result\":{\"tools\":[{\"name\":\"pm_wait\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"role\":{}}}}]}}");
                    }
                }
                default -> response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{}}");
            }
        };
    }

    @Test
    @DisplayName("writes only newline-framed JSON-RPC to stdout and ends on stdin EOF")
    void hygieneAndTermination() throws Exception {
        fixture.start(mcp());
        try (var process = relay(environment())) {
            var lines = new ArrayList<String>();
            process.send(INITIALIZE);
            // The relay forwards requests concurrently: the answer to initialize is awaited before the next request.
            for (var i = 0; i < 3; i++) {
                var line = process.nextLine(WAIT);
                assertNotNull(line, "stdout line " + i + "; stderr: " + process.stderr());
                lines.add(line.text());
                if (i == 0) {
                    process.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                    process.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
                }
            }
            var eofNanos = System.nanoTime();
            process.closeStdin();
            var exit = process.awaitExit(WAIT);
            var exitMillis = (System.nanoTime() - eofNanos) / 1_000_000;

            assertEquals(0, exit);
            var stdout = new String(process.stdout(), StandardCharsets.UTF_8);
            assertEquals(String.join("\n", lines) + "\n", stdout, "stdout holds exactly the three messages");
            for (var line : lines) {
                assertNotNull(JsonTree.asObject(JsonTree.parse(line)), line);
            }
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\"}}", lines.get(0));
            assertTrue(lines.get(1).contains("notifications/progress"));
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"pm_wait\",\"inputSchema\":"
                    + "{\"type\":\"object\",\"properties\":{}}}]}}", lines.get(2));
            assertTrue(exitMillis < 2000, "exit after EOF took " + exitMillis + " ms");
            var values = new LinkedHashMap<String, Object>();
            values.put("mode", binary.mode());
            values.put("stdout_lines", (long) lines.size());
            values.put("exit_after_eof_ms", exitMillis);
            VerificationResults.write("pm-mcp-stdio-hygiene-" + binary.mode(), values, true);
        }
    }

    @Test
    @DisplayName("forwards the host's initialize within the start budget (PM-TECH-3)")
    void startupToFirstForward() throws Exception {
        var server = fixture.start(mcp());
        var samples = new ArrayList<Long>();
        for (var run = 0; run < STARTUP_RUNS; run++) {
            try (var process = relay(environment())) {
                process.send(INITIALIZE);
                var request = server.awaitRequest(WAIT);
                assertNotNull(request, "no request forwarded; stderr: " + process.stderr());
                samples.add((request.receivedNanos() - process.startNanos()) / 1000);
                assertNotNull(process.nextLine(WAIT));
                process.closeStdin();
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
        VerificationResults.write("pm-tech-3-relay-first-forward-" + binary.mode(), values, pass);
        if (binary.nativeImage()) {
            assertTrue(pass, "median start-up to first forwarded request " + median / 1000.0 + " ms");
        }
    }

    @Test
    @DisplayName("starts pm-mcpd detached on demand; killing the relay does not reach it (gate 8)")
    void detachedStart() throws Exception {
        var staged = StagedDaemon.stage(fixture.base().getParent());
        var environment = environment();
        environment.putAll(staged.environment());
        try (var process = relay(environment)) {
            process.send(INITIALIZE);
            var started = staged.awaitStart(WAIT);
            assertNotNull(started, "pm-mcpd was not started; stderr: " + process.stderr());
            var startedNanos = System.nanoTime();
            fixture.start(mcp());
            var line = process.nextLine(WAIT);
            assertNotNull(line, "no response; stderr: " + process.stderr());
            var roundTripMs = (line.arrivalNanos() - startedNanos) / 1_000_000;
            var relayPgid = processGroup(process.process().pid());

            process.process().destroyForcibly();
            process.awaitExit(WAIT);
            Thread.sleep(200);

            try {
                assertEquals(started[0], started[1], "pm-mcpd leads its own process group (setsid)");
                assertNotEquals(relayPgid, started[1]);
                assertTrue(staged.alive(), "pm-mcpd survived the relay's termination");
                var values = new LinkedHashMap<String, Object>();
                values.put("mode", binary.mode());
                values.put("daemon_pid", started[0]);
                values.put("daemon_pgid", started[1]);
                values.put("relay_pgid", relayPgid);
                values.put("survived_relay_kill", staged.alive());
                values.put("response_after_runtime_up_ms", roundTripMs);
                VerificationResults.write("gate8-detached-start-pm-mcp-" + binary.mode(), values, true);
            } finally {
                staged.kill();
            }
        }
    }

    @Test
    @DisplayName("refuses a session relay inside a worker with nothing on stdout")
    void refusesInsideWorker() throws Exception {
        var environment = environment();
        environment.put("PM_MCP_JOB_ID", "job-1");
        try (var process = relay(environment)) {
            assertEquals(ServeCommand.EXIT_REFUSED, process.awaitExit(WAIT));
            assertEquals(0, process.stdout().length);
            assertTrue(process.stderr().contains("PM_MCP_JOB_ID is set"));
        }
    }

    private static long processGroup(long pid) throws IOException, InterruptedException {
        var ps = new ProcessBuilder(List.of("ps", "-o", "pgid=", "-p", Long.toString(pid))).start();
        var text = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        ps.waitFor();
        return Long.parseLong(text);
    }
}
