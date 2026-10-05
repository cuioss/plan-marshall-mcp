/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import de.cuioss.pm.api.PmVersion;
import de.cuioss.pm.api.testsupport.RuntimeFixture;

@DisplayName("pm-mcp command line")
@EnabledOnOs({OS.MAC, OS.LINUX})
class PmMcpTest {

    private RuntimeFixture fixture;
    private LineSink out;
    private LineSink err;

    @BeforeEach
    void setUp() throws IOException {
        fixture = RuntimeFixture.create();
        out = new LineSink();
        err = new LineSink();
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    private int run(Map<String, String> environment, InputStream stdin, String... args) {
        var context = new CliContext(environment, stdin, Path.of("").toAbsolutePath(), fixture.base().getParent());
        return PmMcp.execute(context, out.writer(), err.writer(), args);
    }

    private int run(String... args) {
        return run(fixture.environment(), new ByteArrayInputStream(new byte[0]), args);
    }

    @Test
    @DisplayName("--version prints the release version")
    void version() throws Exception {
        assertEquals(0, run("--version"));
        assertEquals("pm-mcp " + PmVersion.current(), out.next(1));
    }

    @Nested
    @DisplayName("serve refuses")
    class Refusals {

        @ParameterizedTest
        @ValueSource(strings = {"", "bogus", "--job"})
        @DisplayName("a session without a known client and a worker without its arguments")
        void usage(String argument) {
            var args = argument.isEmpty() ? new String[]{"serve"} : argument.startsWith("--")
                    ? new String[]{"serve", argument} : new String[]{"serve", "--client", argument};

            assertEquals(ServeCommand.EXIT_REFUSED, run(args));
            assertTrue(err.text().startsWith("pm-mcp serve: "));
            assertEquals("", out.text());
        }

        @Test
        @DisplayName("a session relay inside a worker (PM_MCP_JOB_ID set without --job)")
        void insideWorker() {
            var environment = new HashMap<>(fixture.environment());
            environment.put("PM_MCP_JOB_ID", "job-1");

            assertEquals(ServeCommand.EXIT_REFUSED,
                    run(environment, new ByteArrayInputStream(new byte[0]), "serve", "--client", "claude"));
            assertTrue(err.text().contains("PM_MCP_JOB_ID is set"));
            assertEquals("", out.text());
        }

        @Test
        @DisplayName("a session relay with a socket argument and a worker relay without a token")
        void mixed() {
            assertEquals(ServeCommand.EXIT_REFUSED, run("serve", "--client", "claude", "--socket", "/s"));
            assertEquals(ServeCommand.EXIT_REFUSED, run("serve", "--job", "--socket", "/s"));
        }

        @Test
        @DisplayName("fails when the working directory does not exist")
        void missingWorkingDirectory() {
            var context = new CliContext(fixture.environment(), new ByteArrayInputStream(new byte[0]),
                    Path.of("/nonexistent/dir"), fixture.base().getParent());

            assertEquals(ServeCommand.EXIT_FAILURE, PmMcp.execute(context, out.writer(), err.writer(), "serve",
                    "--client", "claude"));
        }
    }

    @Nested
    @DisplayName("serve relays")
    class Relays {

        @Test
        @DisplayName("a session through the runtime token until stdin closes")
        void session() throws Exception {
            var server = fixture.start((request, response) -> response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
            var host = new PipedOutputStream();
            var stdin = new PipedInputStream(host);
            var exit = new AtomicInteger(-1);
            var relay = Thread.ofPlatform().start(() -> exit.set(run(fixture.environment(), stdin, "serve", "--client", "codex")));

            host.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
            host.flush();
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", out.next(5));
            host.close();
            relay.join(5000);

            assertEquals(0, exit.get());
            var request = server.awaitRequest(Duration.ofSeconds(1));
            assertEquals("codex", request.header("PM-MCP-Client"));
            assertEquals(Workspace.resolve(Path.of("").toAbsolutePath()).toString(), request.header("PM-MCP-Workspace"));
        }

        @Test
        @DisplayName("a worker through its job token, reading no file of PM_MCP_BASE")
        void worker() throws Exception {
            var server = fixture.start((request, response) -> response.json(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
            fixture.acceptJobToken("jt");
            Files.delete(fixture.paths().runtimeToken());
            var environment = new HashMap<String, String>();
            environment.put("PM_MCP_JOB_TOKEN", "jt");
            environment.put("PM_MCP_JOB_ID", "job-7");
            environment.put("PM_MCP_BASE", "/nonexistent");
            var stdin = new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
                    .getBytes(StandardCharsets.UTF_8));
            var exit = new AtomicInteger(-1);
            var relay = Thread.ofPlatform().start(() -> exit.set(run(environment, stdin, "serve", "--job", "--socket",
                    fixture.paths().socket().toString())));

            var request = server.awaitRequest(Duration.ofSeconds(5));
            relay.join(5000);

            assertEquals(0, exit.get());
            assertEquals("job-7", request.header("PM-MCP-Generation"));
            assertEquals("jt", request.header("PM-MCP-Job-Token"));
        }
    }

    @Nested
    @DisplayName("Workspace")
    class WorkspaceResolution {

        @Test
        @DisplayName("is the nearest directory with a .git entry, else the working directory")
        void gitTopLevel() throws Exception {
            var root = fixture.base().getParent().toRealPath();
            var repository = Files.createDirectories(root.resolve("repo"));
            Files.writeString(repository.resolve(".git"), "gitdir: elsewhere");
            var nested = Files.createDirectories(repository.resolve("a/b"));
            var outside = Files.createDirectories(root.resolve("plain"));

            assertEquals(repository, Workspace.resolve(nested));
            assertEquals(repository, Workspace.resolve(repository));
            var resolvedOutside = Workspace.resolve(outside);
            assertTrue(resolvedOutside.equals(outside) || Files.exists(resolvedOutside.resolve(".git")));
        }
    }
}
