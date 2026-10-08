/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import de.planmarshall.api.testsupport.RuntimeFixture;

@DisplayName("RuntimeTokenFile")
@EnabledOnOs({OS.MAC, OS.LINUX})
class RuntimeTokenFileTest {

    private RuntimeFixture fixture;
    private int uid;

    @BeforeEach
    void setUp() throws IOException {
        fixture = RuntimeFixture.create();
        fixture.writeToken(" secret-token\n");
        uid = (Integer) Files.getAttribute(fixture.paths().runtimeToken(), "unix:uid", LinkOption.NOFOLLOW_LINKS);
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    private RuntimeTokenFile file() {
        return new RuntimeTokenFile(fixture.paths(), uid);
    }

    @Test
    @DisplayName("reads the token of a private run/ directory")
    void reads() throws Exception {
        assertEquals("secret-token", file().read());
    }

    @Test
    @DisplayName("refuses another owner")
    void foreignOwner() {
        var e = assertThrows(InsecureRuntimeFileException.class, () -> new RuntimeTokenFile(fixture.paths(), uid + 1).read());

        assertEquals(fixture.paths().runDir(), e.path());
        assertTrue(e.getMessage().contains("owned by uid"));
    }

    @Test
    @DisplayName("refuses a broader directory mode")
    void broadDirectory() throws Exception {
        Files.setPosixFilePermissions(fixture.paths().runDir(), PosixFilePermissions.fromString("rwxr-xr-x"));

        var e = assertThrows(InsecureRuntimeFileException.class, () -> file().read());
        assertTrue(e.getMessage().contains("has mode 0755, expected 0700"));
    }

    @Test
    @DisplayName("refuses a broader token mode")
    void broadToken() throws Exception {
        Files.setPosixFilePermissions(fixture.paths().runtimeToken(), PosixFilePermissions.fromString("rw-r--r--"));

        var e = assertThrows(InsecureRuntimeFileException.class, () -> file().read());
        assertEquals(fixture.paths().runtimeToken(), e.path());
    }

    @Test
    @DisplayName("refuses a token that is a symbolic link")
    void symlinkToken() throws Exception {
        var real = fixture.paths().runDir().resolve("real");
        Files.move(fixture.paths().runtimeToken(), real);
        Files.createSymbolicLink(fixture.paths().runtimeToken(), real);

        var e = assertThrows(InsecureRuntimeFileException.class, () -> file().read());
        assertTrue(e.getMessage().contains("symbolic link"));
    }

    @Test
    @DisplayName("refuses a run/ directory that is a symbolic link")
    void symlinkDirectory() throws Exception {
        var real = fixture.base().resolve("real-run");
        Files.move(fixture.paths().runDir(), real);
        Files.createSymbolicLink(fixture.paths().runDir(), real);

        assertThrows(InsecureRuntimeFileException.class, () -> file().read());
    }

    @Test
    @DisplayName("refuses a token that is no regular file and a run/ that is no directory")
    void wrongTypes() throws Exception {
        Files.delete(fixture.paths().runtimeToken());
        Files.createDirectory(fixture.paths().runtimeToken());
        assertThrows(InsecureRuntimeFileException.class, () -> file().read());

        Files.delete(fixture.paths().runtimeToken());
        Files.delete(fixture.paths().runDir());
        Files.writeString(fixture.paths().runDir(), "x");
        assertThrows(InsecureRuntimeFileException.class, () -> file().read());
    }

    @Test
    @DisplayName("refuses an empty and an over-long token")
    void sizes() throws Exception {
        fixture.writeToken("   ");
        assertThrows(InsecureRuntimeFileException.class, () -> file().read());

        fixture.writeToken("x".repeat(2000));
        assertThrows(InsecureRuntimeFileException.class, () -> file().read());
    }

    @Test
    @DisplayName("reports an absent token as absent")
    void absent() throws Exception {
        Files.delete(fixture.paths().runtimeToken());

        assertThrows(NoSuchFileException.class, () -> file().read());
    }

    @Test
    @DisplayName("formats modes in octal")
    void octal() {
        assertEquals("0700", RuntimeTokenFile.octal(RuntimeTokenFile.DIRECTORY_MODE));
        assertEquals("0600", RuntimeTokenFile.octal(RuntimeTokenFile.FILE_MODE));
    }
}
