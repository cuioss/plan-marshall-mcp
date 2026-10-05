/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Credential naming: accounts and service names")
class CredentialNamingTest {

    @Nested
    @DisplayName("CredentialAccount")
    class Account {

        @Test
        @DisplayName("global and project accounts")
        void accounts() {
            assertEquals("github", CredentialAccount.global("github").account());
            assertEquals("my-proj.1/mcp-server_x", CredentialAccount.project("my-proj.1", "mcp-server_x").account());
        }

        @ParameterizedTest(name = "''{0}''")
        @ValueSource(strings = {"", ".", "..", "a/b", "a b", "ä", "x\u0000", "../etc"})
        @DisplayName("refuses names outside [a-zA-Z0-9._-]")
        void refuses(String name) {
            assertThrows(InvalidCredentialNameException.class, () -> CredentialAccount.global(name));
            assertThrows(InvalidCredentialNameException.class, () -> CredentialAccount.project(name, "github"));
        }

        @Test
        @DisplayName("refuses a missing key")
        void nullKey() {
            assertThrows(InvalidCredentialNameException.class, () -> CredentialAccount.global(null));
        }
    }

    @Nested
    @DisplayName("ServiceName")
    class Service {

        @TempDir
        Path home;

        @Test
        @DisplayName("is de.cuioss.pm-mcp for the default base, also through a symbolic link")
        void defaultBase() throws Exception {
            var base = Files.createDirectories(home.resolve(".plan-marshall-mcp"));
            var link = Files.createSymbolicLink(home.resolve("link"), base);

            assertEquals("de.cuioss.pm-mcp", ServiceName.of(base, home));
            assertEquals("de.cuioss.pm-mcp", ServiceName.of(link, home));
        }

        @Test
        @DisplayName("is qualified by the first 16 hex characters of the SHA-256 of the canonical base")
        void otherBase() {
            assertEquals("de.cuioss.pm-mcp/3e816ceebf0393f4",
                    ServiceName.of(Path.of("/nonexistent/x/../pm-base"), home));
        }
    }
}
