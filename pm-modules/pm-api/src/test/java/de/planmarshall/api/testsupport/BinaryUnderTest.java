/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.testsupport;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The client binary an IT runs as a real process: the native image under {@code target/} when it
 * was built (and {@code -Dpm.it.jvm=true} is not set), otherwise the packaged JAR on a JVM with its
 * dependencies on the class path.
 *
 * @param command     the command prefix
 * @param nativeImage {@code true} for the native image
 */
public record BinaryUnderTest(List<String> command, boolean nativeImage) {

    /**
     * @param binaryName       the native image name under {@code target/}
     * @param mainClass        the main class of the JAR
     * @param classPathAnchors one class per class-path entry (the module's own, pm-api, picocli,
     *                         jackson-core)
     * @return the binary to run
     */
    public static BinaryUnderTest locate(String binaryName, Class<?> mainClass, Class<?>... classPathAnchors) {
        var nativeBinary = Path.of("target", binaryName).toAbsolutePath();
        if (Files.isExecutable(nativeBinary) && !Boolean.getBoolean("pm.it.jvm")) {
            return new BinaryUnderTest(List.of(nativeBinary.toString()), true);
        }
        var entries = new LinkedHashSet<String>();
        entries.add(location(mainClass));
        for (var anchor : classPathAnchors) {
            entries.add(location(anchor));
        }
        var java = ProcessHandle.current().info().command().orElse("java");
        var command = new ArrayList<String>();
        command.add(java);
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(String.join(File.pathSeparator, entries));
        command.add(mainClass.getName());
        return new BinaryUnderTest(List.copyOf(command), false);
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param arguments the arguments
     * @return the full command line
     */
    public List<String> with(String... arguments) {
        var full = new ArrayList<>(command);
        full.addAll(List.of(arguments));
        return full;
    }

    /** @return {@code native} or {@code jvm} */
    public String mode() {
        return nativeImage ? "native" : "jvm";
    }
}
