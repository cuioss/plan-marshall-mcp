/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.start;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;


import lombok.experimental.UtilityClass;

/**
 * Lets the native binary load the Netty transport library from its own directory.
 * <p>
 * Netty first tries {@code System.loadLibrary} (the {@code java.library.path}) and only then extracts the library
 * from the image's resources into the system temporary directory and loads that fresh copy. On macOS loading a
 * freshly written library costs about 250 ms per start (measured), five times the cold-start budget of
 * PM-TECH-3, and it puts native code the runtime executes into the temporary directory. A library shipped beside
 * the binary ({@code <PM_MCP_HOME>/bin/}) is found first; the embedded copy stays the fallback.
 *
 * @since 0.1
 */
@UtilityClass
public final class NativeLibraryPath {

    static final String LIBRARY_PATH = "java.library.path";
    static final String IMAGE_CODE = "org.graalvm.nativeimage.imagecode";

    /**
     * Prepends the directory of the running native executable to {@code java.library.path}; does nothing on a
     * JVM.
     */
    public static void includeExecutableDirectory() {
        if (!"runtime".equals(System.getProperty(IMAGE_CODE))) {
            return;
        }
        ProcessHandle.current().info().command()
                .flatMap(command -> Optional.ofNullable(Path.of(command).getParent()))
                .ifPresent(dir -> System.setProperty(LIBRARY_PATH, prepend(dir, System.getProperty(LIBRARY_PATH))));
    }

    /**
     * @param dir     the directory to search first
     * @param current the current library path, may be {@code null} or empty
     * @return the new library path
     */
    static String prepend(Path dir, String current) {
        return current == null || current.isEmpty() ? dir.toString() : dir + File.pathSeparator + current;
    }
}
