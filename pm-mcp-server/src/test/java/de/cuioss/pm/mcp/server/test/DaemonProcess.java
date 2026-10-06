/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;


import de.cuioss.pm.api.MachinePaths;

/**
 * The packaged {@code pm-mcpd} started as an OS process with its own {@code PM_MCP_BASE}: the native runner when
 * it is the newer build output, otherwise {@code java -jar target/quarkus-app/quarkus-run.jar}.
 */
public final class DaemonProcess implements AutoCloseable {

    private static final Path TARGET = Path.of("target");

    private final Process process;
    private final MachinePaths paths;
    private final Path log;

    private DaemonProcess(Process process, MachinePaths paths, Path log) {
        this.process = process;
        this.paths = paths;
        this.log = log;
    }

    /** @return {@code true} if the native runner is the newer build output */
    public static boolean isNative() {
        var runner = nativeRunner();
        if (runner == null) {
            return false;
        }
        var jar = TARGET.resolve("quarkus-app/quarkus-run.jar");
        try {
            return !Files.exists(jar) || Files.getLastModifiedTime(runner).compareTo(Files.getLastModifiedTime(jar)) > 0;
        } catch (IOException _) {
            return false;
        }
    }

    private static Path nativeRunner() {
        try (var files = Files.list(TARGET)) {
            return files.filter(file -> file.getFileName().toString().endsWith("-runner")
                    && Files.isExecutable(file)).findFirst().orElse(null);
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Places the Netty transport library of the platform beside the native runner, as the release layout ships
     * it beside {@code pm-mcpd} ({@code NativeLibraryPath}); taken from the transport jar on the test classpath.
     */
    static void stageNativeLibrary() {
        var arch = System.getProperty("os.arch").contains("aarch64") ? "aarch_64" : "x86_64";
        var name = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? "libnetty_transport_native_kqueue_" + arch + ".jnilib"
                : "libnetty_transport_native_epoll_" + arch + ".so";
        var target = TARGET.resolve(name);
        if (Files.exists(target)) {
            return;
        }
        try (var in = DaemonProcess.class.getClassLoader().getResourceAsStream("META-INF/native/" + name)) {
            if (in != null) {
                Files.copy(in, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param extra additional arguments (system properties)
     * @return the command line of the packaged daemon
     */
    public static List<String> command(List<String> extra) {
        var command = new ArrayList<String>();
        if (isNative()) {
            stageNativeLibrary();
            command.add(nativeRunner().toAbsolutePath().toString());
        } else {
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(extra);
            command.add("-jar");
            command.add(TARGET.resolve("quarkus-app/quarkus-run.jar").toAbsolutePath().toString());
            return command;
        }
        command.addAll(extra);
        return command;
    }

    /**
     * Starts the daemon without waiting.
     *
     * @param base  the machine root
     * @param extra additional arguments
     * @return the running daemon
     * @throws IOException if the process cannot be started
     */
    public static DaemonProcess start(Path base, List<String> extra) throws IOException {
        var log = Files.createTempFile(TARGET.toAbsolutePath(), "daemon-", ".log");
        var builder = new ProcessBuilder(command(extra)).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put(MachinePaths.ENV_BASE, base.toString());
        return new DaemonProcess(builder.start(), new MachinePaths(base, MachinePaths.Os.current()), log);
    }

    /**
     * Starts the daemon and waits until it is ready.
     *
     * @param base the machine root
     * @return the ready daemon
     * @throws IOException if it does not become ready
     */
    public static DaemonProcess startReady(Path base) throws IOException {
        var daemon = start(base, List.of());
        daemon.awaitReady(Duration.ofSeconds(30));
        return daemon;
    }

    /**
     * Waits until the socket accepts a connection and the token is present.
     *
     * @param timeout the bound
     * @return the time it took in nanoseconds
     * @throws IOException if the daemon does not become ready within the bound
     */
    public long awaitReady(Duration timeout) throws IOException {
        var start = System.nanoTime();
        var deadline = start + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("daemon exited with " + process.exitValue() + ": " + output());
            }
            if (Files.exists(paths.runtimeToken()) && accepts(paths.socket())) {
                return System.nanoTime() - start;
            }
            Thread.onSpinWait();
        }
        throw new IOException("daemon not ready within " + timeout + ": " + output());
    }

    /**
     * @param socket a socket path
     * @return {@code true} if a connection is accepted
     */
    public static boolean accepts(Path socket) {
        if (!Files.exists(socket)) {
            return false;
        }
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    /** @return the machine paths */
    public MachinePaths paths() {
        return paths;
    }

    /** @return the OS process */
    public Process process() {
        return process;
    }

    /** @return the runtime token */
    public String token() {
        return TestRuntime.token(paths);
    }

    /**
     * @param timeout the bound
     * @return the exit code
     * @throws IOException if the process does not exit within the bound
     */
    public int awaitExit(Duration timeout) throws IOException {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("daemon still running after " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return process.exitValue();
    }

    /** Kills the process with SIGKILL and waits for it. */
    public void kill() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return the combined output so far */
    public String output() {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            return e.toString();
        }
    }

    @Override
    public void close() throws IOException {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    kill();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        Files.deleteIfExists(log);
    }
}
