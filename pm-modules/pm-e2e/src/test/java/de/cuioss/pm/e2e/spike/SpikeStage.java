/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;

/**
 * A minimal staged installation for the harness drivers: {@code <home>/bin/pm-mcp} and {@code <home>/bin/pm-exec},
 * plus the daemon command line. The native binaries of the reactor are copied under their release names when all
 * three are built (or {@code -Dspike.layout=native}); otherwise ({@code -Dspike.layout=jvm}) the bin entries are
 * shell wrappers that run the module jars on this JVM.
 *
 * @param home       {@code PM_MCP_HOME}
 * @param nativeMode whether the native binaries are staged
 * @param daemon     the daemon command prefix; system properties follow it
 * @param daemonJar  the Quarkus run jar in JVM mode, {@code null} in native mode
 */
record SpikeStage(Path home, boolean nativeMode, List<String> daemon, Path daemonJar) {

    private static final Pattern PICOCLI_VERSION = Pattern.compile("<version\\.picocli>([^<]+)</version\\.picocli>");

    /** @return the {@code native} or {@code jvm} label of the layout */
    String mode() {
        return nativeMode ? "native" : "jvm";
    }

    /** @return {@code <home>/bin/pm-mcp} */
    Path relay() {
        return home.resolve("bin/pm-mcp");
    }

    /** @return {@code <home>/bin/pm-exec} */
    Path exec() {
        return home.resolve("bin/pm-exec");
    }

    /**
     * @param properties system properties of the daemon, {@code -Dkey=value}
     * @return the daemon command line
     */
    List<String> daemonCommand(List<String> properties) {
        var command = new ArrayList<String>();
        if (nativeMode) {
            command.addAll(daemon);
            command.addAll(properties);
        } else {
            command.addAll(daemon);
            command.addAll(properties);
            command.add("-jar");
            command.add(daemonJar.toString());
        }
        return command;
    }

    /**
     * @param directory the staging directory
     * @param layout    {@code native}, {@code jvm} or {@code auto}
     * @return the staged installation
     * @throws IOException if a binary is missing or cannot be copied
     */
    static SpikeStage stage(Path directory, String layout) throws IOException {
        var root = repositoryRoot();
        var relay = root.resolve("pm-clients/pm-relay/target/pm-mcp");
        var exec = root.resolve("pm-modules/pm-exec/target/pm-exec");
        var runner = runner(root.resolve("pm-mcp-server/target"));
        var nativeBuilt = Files.isExecutable(relay) && Files.isExecutable(exec) && runner.isPresent();
        var nativeMode = "native".equals(layout) || "auto".equals(layout) && nativeBuilt;
        if (nativeMode && !nativeBuilt) {
            throw new IOException("spike.layout=native, but not all native binaries are built (pm-mcp, pm-exec, "
                    + "pm-mcp-server runner); run ./mvnw package -Pnative first");
        }
        var bin = Files.createDirectories(directory.resolve("home/bin"));
        if (nativeMode) {
            copy(relay, bin.resolve("pm-mcp"));
            copy(exec, bin.resolve("pm-exec"));
            copy(runner.get(), bin.resolve("pm-mcpd"));
            return new SpikeStage(bin.getParent(), true, List.of(bin.resolve("pm-mcpd").toString()), null);
        }
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var relayClassPath = String.join(File.pathSeparator, List.of(
                jar(root.resolve("pm-clients/pm-relay/target"), "pm-relay").toString(),
                jar(root.resolve("pm-modules/pm-api/target"), "pm-api").toString(), picocli(root).toString(),
                location(JsonFactory.class).toString()));
        wrapper(bin.resolve("pm-mcp"), java, relayClassPath, "de.cuioss.pm.relay.PmMcp");
        wrapper(bin.resolve("pm-exec"), java, jar(root.resolve("pm-modules/pm-exec/target"), "pm-exec").toString(),
                "de.cuioss.pm.exec.PmExec");
        var quarkus = root.resolve("pm-mcp-server/target/quarkus-app/quarkus-run.jar");
        if (!Files.exists(quarkus)) {
            throw new IOException("missing " + quarkus + "; run ./mvnw package -pl pm-mcp-server -am -DskipTests");
        }
        return new SpikeStage(bin.getParent(), false, List.of(java), quarkus);
    }

    /** @return the repository root, found upwards from the working directory */
    static Path repositoryRoot() throws IOException {
        for (var dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("pm-mcp-server")) && Files.isDirectory(dir.resolve("pm-clients"))) {
                return dir;
            }
        }
        throw new IOException("repository root not found above " + Path.of("").toAbsolutePath());
    }

    private static Optional<Path> runner(Path target) throws IOException {
        if (!Files.isDirectory(target)) {
            return Optional.empty();
        }
        try (var files = Files.list(target)) {
            return files.filter(file -> file.getFileName().toString().endsWith("-runner") && Files.isExecutable(file))
                    .findFirst();
        }
    }

    private static Path jar(Path target, String artifact) throws IOException {
        if (Files.isDirectory(target)) {
            try (var files = Files.list(target)) {
                var found = files.filter(file -> {
                    var name = file.getFileName().toString();
                    return name.startsWith(artifact + "-") && name.endsWith(".jar") && !name.endsWith("-sources.jar")
                            && !name.endsWith("-javadoc.jar") && !name.endsWith("-tests.jar");
                }).findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            }
        }
        throw new IOException("no " + artifact + " jar in " + target + "; run ./mvnw package -DskipTests first");
    }

    private static Path picocli(Path root) throws IOException {
        var matcher = PICOCLI_VERSION.matcher(Files.readString(root.resolve("pom.xml")));
        if (!matcher.find()) {
            throw new IOException("version.picocli not found in the root pom");
        }
        var version = matcher.group(1);
        var repository = Optional.ofNullable(System.getProperty("maven.repo.local")).map(Path::of)
                .orElse(Path.of(System.getProperty("user.home"), ".m2", "repository"));
        var jar = repository.resolve("info/picocli/picocli/" + version + "/picocli-" + version + ".jar");
        if (!Files.exists(jar)) {
            throw new IOException("missing " + jar);
        }
        return jar;
    }

    private static Path location(Class<?> type) throws IOException {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static void wrapper(Path file, String java, String classPath, String mainClass) throws IOException {
        Files.writeString(file, "#!/bin/sh\nexec '" + java + "' --enable-native-access=ALL-UNNAMED -cp '" + classPath
                + "' " + mainClass + " \"$@\"\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
