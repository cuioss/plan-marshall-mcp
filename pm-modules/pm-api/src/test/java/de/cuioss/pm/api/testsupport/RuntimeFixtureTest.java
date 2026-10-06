/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuntimeFixture")
class RuntimeFixtureTest {

    @Test
    @DisplayName("writes the token 0600 into a base and a run directory of mode 0700")
    void shouldWriteTokenWithPrivateModes() throws Exception {
        var fixture = RuntimeFixture.create();
        try {
            fixture.writeToken("t0ken");

            var paths = fixture.paths();
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fixture.base())));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.runDir())));
            assertEquals("rw-------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.runtimeToken())));
            assertEquals("t0ken", Files.readString(paths.runtimeToken()));
        } finally {
            fixture.close();
        }
    }
}
