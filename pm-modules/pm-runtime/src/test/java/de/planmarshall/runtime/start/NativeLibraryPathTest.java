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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Native library path")
class NativeLibraryPathTest {

    @Test
    @DisplayName("prepends the executable directory to the library path")
    void shouldPrependLibraryPath() {
        var dir = Path.of("/opt/pm/bin");

        assertEquals("/opt/pm/bin", NativeLibraryPath.prepend(dir, null));
        assertEquals("/opt/pm/bin", NativeLibraryPath.prepend(dir, ""));
        assertEquals("/opt/pm/bin" + File.pathSeparator + "/usr/lib", NativeLibraryPath.prepend(dir, "/usr/lib"));
    }

    @Test
    @DisplayName("leaves the library path of a JVM alone")
    void shouldIgnoreJvm() {
        var before = System.getProperty(NativeLibraryPath.LIBRARY_PATH);

        NativeLibraryPath.includeExecutableDirectory();

        assertEquals(before, System.getProperty(NativeLibraryPath.LIBRARY_PATH));
    }
}
