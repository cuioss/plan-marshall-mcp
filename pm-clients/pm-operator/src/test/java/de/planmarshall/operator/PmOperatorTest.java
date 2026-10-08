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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import de.planmarshall.api.PmVersion;
import de.planmarshall.api.testsupport.FakeRuntimeServer;
import de.planmarshall.api.testsupport.RuntimeFixture;

@DisplayName("pm-operator command line")
@EnabledOnOs({OS.MAC, OS.LINUX})
class PmOperatorTest {

    static final String STATUS = "{\"version\":\"0.1\",\"pid\":42,\"listener\":\"unix\",\"socket_path\":\"/s\","
            + "\"started_at\":\"2026-10-05T10:00:00Z\",\"web\":{\"enabled\":true,\"lan\":false,\"port\":7420,\"open\":true}}";

    private RuntimeFixture fixture;
    private StringWriter out;
    private StringWriter err;

    @BeforeEach
    void setUp() throws IOException {
        fixture = RuntimeFixture.create();
        out = new StringWriter();
        err = new StringWriter();
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    private int run(String... args) {
        var context = new OperatorContext(fixture.environment(), fixture.base().getParent());
        return PmOperator.execute(context, new PrintWriter(out), new PrintWriter(err), args);
    }

    /** Writes a live runtime record naming this process. */
    static void writeLiveRecord(RuntimeFixture fixture) throws IOException {
        var self = ProcessHandle.current();
        Files.createDirectories(fixture.paths().stateDir());
        Files.writeString(fixture.paths().runtimeRecord(), "{\"pid\":" + self.pid() + ",\"start_instant\":\""
                + self.info().startInstant().orElseThrow() + "\"}");
    }

    private FakeRuntimeServer runtime(FakeRuntimeServer.Handler handler) throws IOException {
        writeLiveRecord(fixture);
        return fixture.start(handler);
    }

    @Test
    @DisplayName("--version prints the release version")
    void version() {
        assertEquals(0, run("--version"));
        assertEquals("pm-operator " + PmVersion.current(), out.toString().strip());
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("reports not running without a runtime record, starting and writing nothing")
        void notRunning() {
            assertEquals(0, run("status"));
            assertEquals("runtime   not running", out.toString().strip());
            assertFalse(Files.exists(fixture.base()));
        }

        @Test
        @DisplayName("reports not running for a record of a dead process")
        void deadRecord() throws Exception {
            Files.createDirectories(fixture.paths().stateDir());
            Files.writeString(fixture.paths().runtimeRecord(), "{\"pid\":" + (Integer.MAX_VALUE - 1) + "}");

            assertEquals(0, run("status", "--json"));
            assertEquals("{\"running\":false}", out.toString().strip());
        }

        @Test
        @DisplayName("reports not running when the recorded runtime does not answer")
        void silentRecord() throws Exception {
            writeLiveRecord(fixture);
            fixture.writeToken("t");

            assertEquals(0, run("status"));
            assertEquals("runtime   not running", out.toString().strip());
        }

        @Test
        @DisplayName("prints the status of a running runtime readably and as JSON")
        void running() throws Exception {
            var server = runtime((request, response) -> response.json(200, STATUS));

            assertEquals(0, run("status"));
            var text = out.toString();
            assertTrue(text.contains("runtime   running"));
            assertTrue(text.contains("pid       42"));
            assertTrue(text.contains("web       enabled (loopback), port 7420, open"));
            assertTrue(server.awaitRequest(Duration.ofSeconds(1)).header("User-Agent").startsWith("pm-operator/"));

            out.getBuffer().setLength(0);
            assertEquals(0, run("status", "--json"));
            assertEquals(STATUS, out.toString().strip());
        }

        @Test
        @DisplayName("fails on an unexpected answer")
        void refused() throws Exception {
            runtime((request, response) -> response.json(500, "{\"code\":\"internal_fault\"}"));

            assertEquals(PmOperator.EXIT_FAILURE, run("status"));
            assertTrue(err.toString().contains("HTTP 500"));

            fixture.stop();
            fixture.start((request, response) -> response.json(200, "[]"));
            assertEquals(PmOperator.EXIT_FAILURE, run("status"));
        }
    }

    @Nested
    @DisplayName("web")
    class Web {

        @Test
        @DisplayName("enable sends PUT /api/v1/web with lan and port and prints the listener")
        void enable() throws Exception {
            var server = runtime((request, response) -> response.json(200,
                    "{\"enabled\":true,\"lan\":true,\"port\":8443,\"open\":true}"));

            assertEquals(0, run("web", "enable", "--lan", "--port", "8443"));

            var request = server.awaitRequest(Duration.ofSeconds(1));
            assertEquals("PUT", request.method());
            assertEquals("/api/v1/web", request.target());
            assertEquals("{\"enabled\":true,\"lan\":true,\"port\":8443}", request.bodyText());
            assertEquals("web       enabled (lan), port 8443, open", out.toString().strip());
        }

        @Test
        @DisplayName("disable sends enabled false with the default port")
        void disable() throws Exception {
            var server = runtime((request, response) -> response.json(200,
                    "{\"enabled\":false,\"lan\":false,\"port\":7420,\"open\":false}"));

            assertEquals(0, run("web", "disable"));

            assertEquals("{\"enabled\":false,\"lan\":false,\"port\":7420}",
                    server.awaitRequest(Duration.ofSeconds(1)).bodyText());
            assertEquals("web       disabled", out.toString().strip());
        }

        @Test
        @DisplayName("reports a conflict")
        void conflict() throws Exception {
            runtime((request, response) -> response.json(409, "{\"code\":\"web_listener_conflict\"}"));

            assertEquals(PmOperator.EXIT_FAILURE, run("web", "enable"));
            assertTrue(err.toString().contains("web_listener_conflict"));
        }

        @Test
        @DisplayName("describes a missing and a closed listener")
        void describe() {
            assertEquals("-", WebCommand.describe(null));
            assertEquals("enabled (loopback), port 1, not open", WebCommand.describe(Map.of("enabled", true,
                    "port", 1)));
        }
    }

    @Nested
    @DisplayName("runtime")
    class Runtime {

        @Test
        @DisplayName("start reports a runtime that answers")
        void start() throws Exception {
            runtime((request, response) -> response.json(200, STATUS));

            assertEquals(0, run("runtime", "start"));
            assertEquals("runtime   running (pid 42)", out.toString().strip());
        }

        @Test
        @DisplayName("stop posts the stop request")
        void stop() throws Exception {
            var server = runtime((request, response) -> response.send(202, null, ""));

            assertEquals(0, run("runtime", "stop"));

            var request = server.awaitRequest(Duration.ofSeconds(1));
            assertEquals("POST", request.method());
            assertEquals("/api/v1/runtime/stop", request.target());
            assertEquals("runtime   stopping", out.toString().strip());
        }

        @Test
        @DisplayName("stop without a runtime starts none")
        void stopWithoutRuntime() {
            assertEquals(0, run("runtime", "stop"));
            assertEquals("runtime   not running", out.toString().strip());
        }

        @Test
        @DisplayName("stop reports an unexpected answer")
        void stopRefused() throws Exception {
            runtime((request, response) -> response.send(500, null, ""));

            assertEquals(PmOperator.EXIT_FAILURE, run("runtime", "stop"));
        }
    }
}
