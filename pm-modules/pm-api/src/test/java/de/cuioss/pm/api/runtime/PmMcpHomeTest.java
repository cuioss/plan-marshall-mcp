/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisplayName("PmMcpHome")
class PmMcpHomeTest {

    private Path directory;

    @BeforeEach
    void setUp() throws IOException {
        directory = Files.createTempDirectory("pm-home").toRealPath();
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(directory)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    @DisplayName("takes PM_MCP_HOME when set")
    void environment() throws Exception {
        var home = PmMcpHome.resolve(Map.of("PM_MCP_HOME", "/opt/pm/../pm-1"), Optional.empty());

        assertEquals(Path.of("/opt/pm-1"), home.directory());
        assertEquals(Path.of("/opt/pm-1/bin/pm-mcpd"), home.daemon());
        assertEquals(Path.of("/opt/pm-1/bin/pm-exec"), home.binary("pm-exec"));
        assertEquals(home, PmMcpHome.current(Map.of("PM_MCP_HOME", "/opt/pm-1")));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @DisplayName("resolves a chain of two symlinks to the installation of the executable")
    void symlinks() throws Exception {
        var installation = directory.resolve("pm-mcp-1.0");
        var binary = Files.createDirectories(installation.resolve("bin")).resolve("pm-mcp");
        Files.writeString(binary, "");
        var userBin = Files.createDirectories(directory.resolve("local/bin"));
        var middle = Files.createSymbolicLink(directory.resolve("middle"), binary);
        var entry = Files.createSymbolicLink(userBin.resolve("pm-mcp"), middle);

        var home = PmMcpHome.resolve(Map.of("PM_MCP_HOME", " "), Optional.of(entry));

        assertEquals(installation, home.directory());
    }

    @Test
    @DisplayName("fails without variable and executable, and for an executable at the root")
    void unresolvable() {
        var environment = Map.<String, String>of();
        var root = Optional.of(Path.of("/"));

        assertThrows(RuntimeUnavailableException.class, () -> PmMcpHome.resolve(environment, Optional.empty()));
        assertThrows(RuntimeUnavailableException.class, () -> PmMcpHome.resolve(environment, root));
    }
}
