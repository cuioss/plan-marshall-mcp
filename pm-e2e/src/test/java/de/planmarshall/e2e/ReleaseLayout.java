/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;

/**
 * The release layout under test: a temporary {@code <PM_MCP_HOME>/bin} with {@code pm-mcp}, {@code pm-operator},
 * {@code pm-exec} and {@code pm-mcpd}, and a short private {@code PM_MCP_BASE} below {@code /tmp} (the socket path
 * must fit {@code sun_path}).
 * <ul>
 * <li>{@link Mode#NATIVE}: copies of the native image {@code pm-mcpd} of this build and of the three native client
 * binaries of a checkout of {@code pm-mcp-clients}, plus the Netty transport library beside {@code pm-mcpd} as the
 * release ships it. Used when {@code pm-mcpd} is the current build output (its runner newer than the runner JAR)
 * and the client binaries are built; otherwise the test is skipped.</li>
 * <li>{@link Mode#JVM}: launcher scripts; {@code pm-mcpd} runs {@code java -jar quarkus-run.jar}, the clients run
 * their JARs (staged by the {@code maven-dependency-plugin} into {@code target/e2e-jvm-lib}). Used when the runner
 * JAR is the current build output.</li>
 * </ul>
 */
final class ReleaseLayout implements AutoCloseable {

    /** How the binaries are staged. */
    enum Mode {
        /** Launcher scripts over the JARs. */
        JVM,
        /** The native images. */
        NATIVE;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A job token no job of the staged daemon is bound to. */
    static final String JOB_TOKEN = "job-token-for-e2e-0123456789abcdef01";
    /** The job id a worker relay is started with. */
    static final String JOB_ID = "j-e2e0001";

    static final Duration WAIT = Duration.ofSeconds(60);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();
    private static final System.Logger LOGGER = System.getLogger(ReleaseLayout.class.getName());
    private static final Path SERVER_TARGET = ROOT.resolve("pm-mcp-server/target");
    /**
     * The checkout of {@code pm-mcp-clients} whose native build supplies {@code pm-mcp}, {@code pm-operator} and
     * {@code pm-exec}: the system property {@code pm.clients.checkout}, else the directory beside this repository.
     */
    private static final Path CLIENTS = Path.of(System.getProperty("pm.clients.checkout",
            ROOT.resolveSibling("pm-mcp-clients").toString())).toAbsolutePath().normalize();
    private static final Path RUNNER_JAR = SERVER_TARGET.resolve("quarkus-app/quarkus-run.jar");
    private static final Path JVM_LIB = Path.of("target", "e2e-jvm-lib").toAbsolutePath();
    private static final boolean MACOS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");

    private final Mode mode;
    private final Path home;
    private final Path base;
    private final Path transportLibrary;

    private ReleaseLayout(Mode mode, Path home, Path base, Path transportLibrary) {
        this.mode = mode;
        this.home = home;
        this.base = base;
        this.transportLibrary = transportLibrary;
    }

    /**
     * Stages the layout, or aborts the test when the binaries of the mode are not the current build output.
     *
     * @param mode the mode
     * @return the staged layout
     * @throws IOException on a staging failure
     */
    static ReleaseLayout stage(Mode mode) throws IOException {
        var runner = nativeRunner();
        var nativeCurrent = runner.isPresent() && isNewer(runner.get(), RUNNER_JAR);
        var home = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "e2e-home-");
        var bin = Files.createDirectories(home.resolve("bin"));
        Path library = null;
        if (mode == Mode.NATIVE) {
            var sources = new LinkedHashMap<String, Path>();
            sources.put("pm-mcp", CLIENTS.resolve("pm-relay/target/pm-mcp"));
            sources.put("pm-operator", CLIENTS.resolve("pm-operator/target/pm-operator"));
            sources.put("pm-exec", CLIENTS.resolve("pm-exec/target/pm-exec"));
            sources.put("pm-mcpd", runner.orElse(SERVER_TARGET.resolve("pm-mcp-server-runner")));
            var missing = sources.entrySet().stream().filter(entry -> !Files.isExecutable(entry.getValue()))
                    .map(Map.Entry::getKey).toList();
            abortUnless(home, missing.isEmpty(), "native layout skipped: native binaries " + missing
                    + " not found. pm-mcp, pm-operator and pm-exec come from a checkout of pm-mcp-clients at "
                    + CLIENTS + (Files.isDirectory(CLIENTS) ? ", which is not built natively"
                    : ", which does not exist")
                    + "; clone it there (or set -Dpm.clients.checkout) and run './mvnw verify -Pnative' in it");
            abortUnless(home, nativeCurrent, "native layout skipped: the pm-mcpd native runner is older than "
                    + "the runner JAR, so the native binaries are not the current build output");
            for (var entry : sources.entrySet()) {
                Files.copy(entry.getValue(), bin.resolve(entry.getKey()), StandardCopyOption.COPY_ATTRIBUTES);
            }
            library = stageTransportLibrary(bin);
        } else {
            abortUnless(home, Files.isRegularFile(RUNNER_JAR) && !nativeCurrent,
                    "JVM layout skipped: the runner JAR " + RUNNER_JAR + " is absent or older than the native runner");
            abortUnless(home, Files.isDirectory(JVM_LIB), "JVM layout skipped: " + JVM_LIB + " not staged");
            var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            script(bin.resolve("pm-mcpd"), quote(java) + " -jar " + quote(RUNNER_JAR.toString()));
            var clientPath = String.join(":", List.of(lib("pm-relay"), lib("pm-operator"), lib("pm-api"),
                    lib("picocli"), lib("jackson-core")));
            var client = quote(java) + " --enable-native-access=ALL-UNNAMED -cp " + quote(clientPath) + " ";
            script(bin.resolve("pm-mcp"), client + "de.planmarshall.relay.PmMcp");
            script(bin.resolve("pm-operator"), client + "de.planmarshall.operator.PmOperator");
            script(bin.resolve("pm-exec"), quote(java) + " --enable-native-access=ALL-UNNAMED -cp "
                    + quote(lib("pm-exec")) + " de.planmarshall.exec.PmExec");
        }
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return new ReleaseLayout(mode, home, Path.of("/tmp", "pme-" + HexFormat.of().formatHex(bytes)), library);
    }

    /**
     * Skips the test when a layout cannot be staged, and writes the reason to the build output: a skipped test is
     * only a count in the summary otherwise, and a native layout that never runs would go unnoticed.
     */
    private static void abortUnless(Path home, boolean condition, String message) {
        if (!condition) {
            delete(home);
            LOGGER.log(System.Logger.Level.WARNING, "pm-e2e: {0}", message);
            Assumptions.abort(message);
        }
    }

    private static String lib(String name) {
        return JVM_LIB.resolve(name + ".jar").toString();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void script(Path file, String command) throws IOException {
        Files.writeString(file, "#!/bin/sh\nexec " + command + " \"$@\"\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static Optional<Path> nativeRunner() throws IOException {
        if (!Files.isDirectory(SERVER_TARGET)) {
            return Optional.empty();
        }
        try (var files = Files.list(SERVER_TARGET)) {
            return files.filter(file -> file.getFileName().toString().endsWith("-runner") && Files.isExecutable(file))
                    .findFirst();
        }
    }

    private static boolean isNewer(Path file, Path than) throws IOException {
        return !Files.exists(than) || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(than)) > 0;
    }

    /**
     * Places the Netty transport library of the platform beside {@code pm-mcpd}, taken from the transport JAR the
     * native build of {@code pm-mcp-server} used (its native-image source JAR, else the runner's lib directory).
     *
     * @param bin the bin directory
     * @return the staged library, or {@code null} if no transport JAR was found (the binary then falls back to its
     *         embedded copy)
     * @throws IOException on a copy failure
     */
    private static Path stageTransportLibrary(Path bin) throws IOException {
        var arch = System.getProperty("os.arch").contains("aarch64") ? "aarch_64" : "x86_64";
        var name = MACOS ? "libnetty_transport_native_kqueue_" + arch + ".jnilib"
                : "libnetty_transport_native_epoll_" + arch + ".so";
        var artifact = MACOS ? "netty-transport-native-kqueue" : "netty-transport-native-epoll";
        var classifier = (MACOS ? "osx-" : "linux-") + arch + ".jar";
        var directories = new ArrayList<Path>();
        try (var files = Files.list(SERVER_TARGET)) {
            files.filter(file -> file.getFileName().toString().endsWith("-native-image-source-jar"))
                    .map(file -> file.resolve("lib")).forEach(directories::add);
        }
        directories.add(SERVER_TARGET.resolve("quarkus-app/lib/main"));
        for (var directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            Optional<Path> jar;
            try (var files = Files.list(directory)) {
                jar = files.filter(file -> file.getFileName().toString().contains(artifact)
                        && file.getFileName().toString().endsWith(classifier)).findFirst();
            }
            if (jar.isPresent()) {
                try (var zip = FileSystems.newFileSystem(jar.get())) {
                    var entry = zip.getPath("META-INF", "native", name);
                    if (Files.exists(entry)) {
                        return Files.copy(entry, bin.resolve(name));
                    }
                }
            }
        }
        return null;
    }

    /** @return the mode */
    Mode mode() {
        return mode;
    }

    /** @return {@code <PM_MCP_HOME>} */
    Path home() {
        return home;
    }

    /** @return {@code <PM_MCP_BASE>} */
    Path base() {
        return base;
    }

    /** @return {@code <PM_MCP_BASE>/run/runtime.sock} */
    Path socket() {
        return base.resolve("run/runtime.sock");
    }

    /** @return the Netty transport library staged beside {@code pm-mcpd}, or {@code null} */
    Path transportLibrary() {
        return transportLibrary;
    }

    /**
     * @param name a binary name
     * @return {@code <PM_MCP_HOME>/bin/<name>}
     */
    Path binary(String name) {
        return home.resolve("bin").resolve(name);
    }

    /**
     * @param name      a binary name
     * @param arguments its arguments
     * @return the command line
     */
    List<String> command(String name, String... arguments) {
        var command = new ArrayList<String>();
        command.add(binary(name).toString());
        command.addAll(List.of(arguments));
        return command;
    }

    /**
     * The environment of every process of the layout. A native client resolves {@code PM_MCP_HOME} from its own
     * executable as in a release; the JVM launcher scripts run {@code java}, so the variable names it for them.
     *
     * @return the environment
     */
    Map<String, String> environment() {
        var environment = new HashMap<String, String>();
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("HOME", System.getProperty("user.home"));
        environment.put("PM_MCP_BASE", base.toString());
        var tmp = System.getenv("TMPDIR");
        if (tmp != null) {
            environment.put("TMPDIR", tmp);
        }
        if (mode == Mode.JVM) {
            environment.put("PM_MCP_HOME", home.toString());
        }
        return environment;
    }

    /**
     * A finished command.
     *
     * @param exit   the exit code
     * @param stdout the standard output
     * @param stderr the standard error
     * @param millis the wall-clock time
     */
    record Result(int exit, String stdout, String stderr, long millis) {
    }

    /**
     * Runs {@code pm-operator} to completion.
     *
     * @param arguments its arguments
     * @return the result
     * @throws IOException          if it cannot be run or does not finish in time
     * @throws InterruptedException if interrupted
     */
    Result operator(String... arguments) throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command("pm-operator", arguments)).directory(home.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment());
        var start = System.nanoTime();
        var process = builder.start();
        // the started daemon inherits no descriptor of the operator (it is spawned detached), so stdout ends
        var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("pm-operator " + List.of(arguments) + " did not finish within " + WAIT);
        }
        return new Result(process.exitValue(), stdout, stderr, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * @param result an operator result
     * @return its standard output as JSON
     * @throws IOException if it is no JSON
     */
    static JsonNode json(Result result) throws IOException {
        return JSON.readTree(result.stdout());
    }

    /**
     * @return the pid of {@code state/runtime.json}, if present
     * @throws IOException on a read failure
     */
    Optional<Long> runtimePid() throws IOException {
        var record = base.resolve("state/runtime.json");
        if (!Files.exists(record)) {
            return Optional.empty();
        }
        return Optional.of(JSON.readTree(record.toFile()).path("pid").asLong());
    }

    /** @return the runtime log {@code logs/daemon.log}, for diagnostics */
    String daemonLog() {
        try {
            var log = base.resolve("logs/daemon.log");
            return Files.exists(log) ? Files.readString(log) : "(no daemon log)";
        } catch (IOException e) {
            return e.toString();
        }
    }

    /**
     * Waits until a process is gone.
     *
     * @param pid     the pid
     * @param timeout the maximum wait
     * @return {@code true} if it exited in time
     * @throws InterruptedException if interrupted
     */
    static boolean awaitGone(long pid, Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        try {
            var pid = runtimePid();
            if (pid.isPresent() && ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false)) {
                operator("runtime", "stop");
                if (!awaitGone(pid.get(), Duration.ofSeconds(10))) {
                    ProcessHandle.of(pid.get()).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            delete(base);
            delete(home);
        }
    }

    private static void delete(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
