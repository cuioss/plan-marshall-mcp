/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate 3 (Linux) and the process-group contract (all platforms): {@code pm-exec} started as a child
 * process, the native binary {@code target/pm-exec} when it was built ({@code -Pnative}), otherwise
 * the JVM. Writes {@code target/verification-results/gate3-pm-exec-<os>-<native|jvm>.json}.
 */
@DisplayName("pm-exec as a child process")
class PmExecIT {

    private static final Pattern REPORT_PID = Pattern.compile("\"pid\":(\\d+)");
    private static final Pattern PROBE_ABI = Pattern.compile("\"landlock_abi\":(\\d+)");
    /** The first Landlock ABI that governs {@code connect(2)} to pathname Unix sockets. */
    private static final int RESOLVE_UNIX_ABI = 9;
    private static final Map<String, Object> RESULTS = new LinkedHashMap<>();
    private static boolean pass = true;

    @TempDir
    Path temp;

    private Path base;
    private Path project;
    private Path other;

    record Result(int exit, String out, String err, long millis) {

        String reportLine() {
            return err.lines().findFirst().orElse("");
        }
    }

    @BeforeEach
    void fixture() throws IOException {
        var root = temp.toRealPath();
        base = Files.createDirectories(root.resolve("base"));
        Files.createDirectories(base.resolve("run/jobs/l1"));
        Files.writeString(base.resolve("run/runtime.token"), "runtime-token-canary");
        Files.writeString(base.resolve("run/jobs/l1/prompt.md"), "prompt-canary");
        Files.createDirectories(base.resolve("credentials"));
        Files.writeString(base.resolve("credentials/github.json"), "credential-canary");
        project = Files.createDirectories(root.resolve("project"));
        other = Files.createDirectories(root.resolve("other-project"));
        Files.writeString(root.resolve("sibling.txt"), "sibling-canary");
    }

    static List<String> launcher() {
        var nativeBinary = Path.of(System.getProperty("pm.exec.native", "target/pm-exec"));
        if (Files.isExecutable(nativeBinary)) {
            return List.of(nativeBinary.toAbsolutePath().toString());
        }
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("pm.exec.classes", "target/classes"), PmExec.class.getName());
    }

    static String mode() {
        return launcher().size() == 1 ? "native" : "jvm";
    }

    private Result run(List<String> options, String... program) throws IOException, InterruptedException {
        return run(options, null, program);
    }

    private Result run(List<String> options, Map<String, String> environment, String... program)
            throws IOException, InterruptedException {
        var command = new ArrayList<>(launcher());
        command.addAll(options);
        if (program.length > 0) {
            command.add("--");
            command.addAll(List.of(program));
        }
        long start = System.nanoTime();
        var builder = new ProcessBuilder(command);
        if (environment != null) {
            builder.environment().clear();
            builder.environment().putAll(environment);
        }
        var process = builder.start();
        process.getOutputStream().close();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "pm-exec did not end");
        return new Result(process.exitValue(), out, err, (System.nanoTime() - start) / 1_000_000);
    }

    private List<String> confinedOptions(String... extra) {
        var options = new ArrayList<>(List.of("--deny-read", base.toString(), "--write", project.toString(),
                "--write", "/dev"));
        options.addAll(List.of(extra));
        return options;
    }

    @AfterAll
    static void record() throws IOException {
        RESULTS.put("mode", mode());
        VerificationResults.write("gate3-pm-exec-" + Os.of(System.getProperty("os.name")).jsonName() + "-" + mode(), RESULTS,
                pass);
    }

    private static void measured(String key, Object value, boolean ok) {
        RESULTS.put(key, value);
        pass &= ok;
    }

    @Test
    @DisplayName("--probe prints the platform JSON")
    void probe() throws Exception {
        var result = run(List.of("--probe"));

        measured("probe", result.out().strip(), result.exit() == 0);
        measured("probe_ms", result.millis(), true);
        assertEquals(0, result.exit(), result.err());
        var os = Os.of(System.getProperty("os.name"));
        assertTrue(result.out().startsWith("{\"os\":\"" + os.jsonName() + "\",\"landlock_abi\":"), result.out());
        if (os == Os.MACOS) {
            assertEquals("{\"os\":\"macos\",\"landlock_abi\":0,\"no_new_privs\":false,\"confinement\":\"unavailable\"}",
                    result.out().strip());
        }
    }

    @Test
    @DisplayName("a plain exec runs the program with its arguments and reports first")
    void plainExec() throws Exception {
        var result = run(confinedOptions(), "/bin/echo", "hello", "a b");

        measured("plain_exec_exit", result.exit(), result.exit() == 0);
        measured("plain_exec_ms", result.millis(), true);
        measured("report_line", result.reportLine(), result.reportLine().startsWith("{\"pm_exec\":\"launched\""));
        assertEquals(0, result.exit(), result.err());
        assertEquals("hello a b", result.out().strip());
        assertTrue(result.reportLine().startsWith("{\"pm_exec\":\"launched\",\"pid\":"), result.err());
    }

    @Test
    @DisplayName("the job is leader of its own process group, its PID the reported one")
    void ownProcessGroup() throws Exception {
        var result = run(confinedOptions(), "/bin/sh", "-c", "echo $$ $(ps -o pgid= -p $$)");

        var ids = result.out().strip().split("\\s+");
        var matcher = REPORT_PID.matcher(result.reportLine());
        boolean ok = ids.length == 2 && ids[0].equals(ids[1]) && matcher.find() && matcher.group(1).equals(ids[0]);
        measured("own_process_group", ok, ok);
        assertEquals(0, result.exit(), result.err());
        assertEquals(ids[0], ids[1], "pid equals pgid");
        assertTrue(ok, "reported pid " + result.reportLine() + " vs " + result.out());
        assertNotEquals(Long.toString(ProcessHandle.current().pid()), ids[1]);
    }

    @Test
    @DisplayName("a missing program exits 127 with an error line")
    void missingProgram() throws Exception {
        var result = run(confinedOptions(), "/nonexistent/program");

        measured("missing_program_exit", result.exit(), result.exit() == 127);
        assertEquals(127, result.exit());
        assertTrue(result.err().contains("\"stage\":\"execve\",\"errno\":2"), result.err());
    }

    @Test
    @DisplayName("a usage error exits 64")
    void usageError() throws Exception {
        var result = run(List.of("--write", "relative"), "/bin/true");

        assertEquals(64, result.exit());
    }

    /**
     * The job environment as the runtime builds it from the operator's: without the session bus address.
     */
    private static Map<String, String> jobEnvironment(Map<String, String> operator) {
        var environment = new LinkedHashMap<>(operator);
        environment.remove("DBUS_SESSION_BUS_ADDRESS");
        return environment;
    }

    private static ServerSocketChannel listen(Path socket) throws IOException {
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        return server;
    }

    private static String[] probeCommand(Path... sockets) {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:-UsePerfData", "-cp", Path.of("target", "test-classes").toAbsolutePath().toString(),
                UnixConnectProbe.class.getName()));
        for (var socket : sockets) {
            command.add(socket.toString());
        }
        return command.toArray(String[]::new);
    }

    @Nested
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Linux Landlock confinement (gate 3, PM-SEC-5)")
    class Landlock {

        @BeforeEach
        void requireLandlock() throws Exception {
            var probe = run(List.of("--probe"));
            assumeTrue(probe.out().contains("\"confinement\":\"confined\""), "Landlock ABI >= 2 needed: " + probe.out());
        }

        @Test
        @DisplayName("a write outside the write set is denied (EACCES)")
        void writeOutsideDenied() throws Exception {
            var target = other.resolve("f");

            var result = run(confinedOptions(), "/bin/sh", "-c", "echo x > " + target);

            boolean denied = result.exit() != 0 && !Files.exists(target)
                    && result.err().contains("Permission denied");
            measured("write_other_project_denied", denied, denied);
            assertTrue(denied, result.err());
            assertTrue(result.reportLine().contains("\"confinement\":\"confined\""), result.reportLine());
        }

        @Test
        @DisplayName("a write into PM_MCP_BASE is denied")
        void writeBaseDenied() throws Exception {
            var target = base.resolve("run/injected");

            var result = run(confinedOptions(), "/bin/sh", "-c", "echo x > " + target);

            boolean denied = result.exit() != 0 && !Files.exists(target);
            measured("write_base_denied", denied, denied);
            assertTrue(denied, result.err());
        }

        @Test
        @DisplayName("reads of the runtime token and a credential file are denied")
        void readBaseDenied() throws Exception {
            var token = run(confinedOptions(), "/bin/cat", base.resolve("run/runtime.token").toString());
            var credential = run(confinedOptions(), "/bin/cat", base.resolve("credentials/github.json").toString());

            boolean denied = token.exit() != 0 && !token.out().contains("canary") && credential.exit() != 0
                    && !credential.out().contains("canary") && token.err().contains("Permission denied");
            measured("read_base_denied", denied, denied);
            assertTrue(denied, token.err() + credential.err());
        }

        @Test
        @DisplayName("a write inside the write set works")
        void writeInsideAllowed() throws Exception {
            var target = project.resolve("f");

            var result = run(confinedOptions(), "/bin/sh", "-c", "echo x > " + target);

            boolean ok = result.exit() == 0 && Files.exists(target);
            measured("write_own_project_allowed", ok, ok);
            assertTrue(ok, result.err());
        }

        @Test
        @DisplayName("a sibling of PM_MCP_BASE stays readable")
        void siblingReadable() throws Exception {
            var result = run(confinedOptions(), "/bin/cat", base.resolveSibling("sibling.txt").toString());

            boolean ok = result.exit() == 0 && result.out().contains("sibling-canary");
            measured("read_sibling_allowed", ok, ok);
            assertTrue(ok, result.err());
        }

        @Test
        @DisplayName("the job-private directory given with --read is readable, not writable")
        void jobPrivateReadOnly() throws Exception {
            var jobDir = base.resolve("run/jobs/l1");
            var options = confinedOptions("--read", jobDir.toString());

            var read = run(options, "/bin/cat", jobDir.resolve("prompt.md").toString());
            var write = run(options, "/bin/sh", "-c", "echo x > " + jobDir.resolve("prompt.md"));

            boolean ok = read.exit() == 0 && read.out().contains("prompt-canary") && write.exit() != 0
                    && "prompt-canary".equals(Files.readString(jobDir.resolve("prompt.md")));
            measured("job_private_read_only", ok, ok);
            assertTrue(ok, read.err() + write.err());
            assertFalse(Files.readString(jobDir.resolve("prompt.md")).contains("x"));
        }

        @Test
        @DisplayName("the session bus in a denied runtime directory: denied from ABI 9, reachable below")
        void sessionBus() throws Exception {
            var probe = run(List.of("--probe"));
            var abiMatcher = PROBE_ABI.matcher(probe.out());
            assertTrue(abiMatcher.find(), probe.out());
            int abi = Integer.parseInt(abiMatcher.group(1));
            var runtimeDir = Files.createDirectories(temp.toRealPath().resolve("xdg-run"));
            var bus = runtimeDir.resolve("bus");
            var relaySocket = base.resolve("run/runtime.sock");
            var projectSocket = project.resolve("tool.sock");
            var session = new LinkedHashMap<>(System.getenv());
            session.put("XDG_RUNTIME_DIR", runtimeDir.toString());
            session.put("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + bus);
            var job = jobEnvironment(session);

            try (var _ = listen(bus); var _ = listen(relaySocket);
                 var _ = listen(projectSocket)) {
                var busRun = run(List.of("--deny-read", runtimeDir.toString(), "--write", project.toString(),
                        "--write", "/dev"), job, probeCommand(bus));
                var relayRun = run(confinedOptions("--read", relaySocket.toString()), job,
                        probeCommand(relaySocket, projectSocket));

                boolean busDenied = busRun.out().contains(bus + " refused");
                boolean expected = abi >= RESOLVE_UNIX_ABI
                        ? busDenied && busRun.out().contains("Permission denied")
                        : busRun.out().contains(bus + " connected");
                boolean dropped = busRun.out().contains("dbus_env absent");
                boolean relayReachable = relayRun.out().contains(relaySocket + " connected")
                        && relayRun.out().contains(projectSocket + " connected");
                measured("session_bus_landlock_abi", abi, true);
                measured("session_bus_connect_denied", busDenied, expected);
                measured("session_bus_env_dropped", dropped, dropped);
                measured("runtime_socket_read_rule_connect", relayReachable, relayReachable);
                assertEquals(0, busRun.exit(), busRun.err());
                assertTrue(expected, "ABI " + abi + ": " + busRun.out() + busRun.err());
                assertTrue(dropped, busRun.out());
                assertTrue(relayReachable, relayRun.out() + relayRun.err());
            }
        }
    }
}
