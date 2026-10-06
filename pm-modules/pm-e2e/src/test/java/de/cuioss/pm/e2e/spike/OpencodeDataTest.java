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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("OpencodeData: per-worker OpenCode data directory")
class OpencodeDataTest {

    @TempDir
    Path temp;

    @Nested
    @DisplayName("the operator's login")
    class OperatorLogin {

        @Test
        @DisplayName("defaults to ~/.local/share/opencode/auth.json")
        void defaultLocation() {
            var home = Path.of("/home/op");

            assertEquals(Path.of("/home/op/.local/share/opencode/auth.json"),
                    OpencodeData.operatorLogin(Map.of(), home));
            assertEquals(Path.of("/home/op/.local/share/opencode/auth.json"),
                    OpencodeData.operatorLogin(Map.of("XDG_DATA_HOME", " "), home));
        }

        @Test
        @DisplayName("follows the driver's XDG_DATA_HOME")
        void xdgDataHome() {
            assertEquals(Path.of("/data/opencode/auth.json"),
                    OpencodeData.operatorLogin(Map.of("XDG_DATA_HOME", "/data"), Path.of("/home/op")));
        }
    }

    @Nested
    @DisplayName("prepare and remove")
    class PrepareAndRemove {

        @Test
        @DisplayName("copies the login with mode 0600 into a 0700 data directory, and removes it again")
        void copyAndRemove() throws Exception {
            var login = Files.writeString(temp.resolve("auth.json"), "{\"zen\":\"secret\"}");
            var workerDir = Files.createDirectories(temp.resolve("w1-g1"));

            var dataHome = OpencodeData.prepare(workerDir, login);

            var copy = dataHome.resolve("opencode/auth.json");
            assertEquals(workerDir.resolve("xdg-data"), dataHome);
            assertEquals("{\"zen\":\"secret\"}", Files.readString(copy));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(copy)));
            assertEquals("rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(dataHome.resolve("opencode"))));
            assertTrue(OpencodeData.removeLogin(dataHome));
            assertFalse(Files.exists(copy));
            assertFalse(OpencodeData.removeLogin(dataHome));
        }

        @Test
        @DisplayName("a second prepare replaces the copy")
        void again() throws Exception {
            var login = Files.writeString(temp.resolve("auth.json"), "old");
            var workerDir = Files.createDirectories(temp.resolve("w1-g2"));
            OpencodeData.prepare(workerDir, login);
            Files.writeString(login, "new");

            var dataHome = OpencodeData.prepare(workerDir, login);

            assertEquals("new", Files.readString(dataHome.resolve("opencode/auth.json")));
        }

        @Test
        @DisplayName("without an operator login the data directory is created empty")
        void noLogin() throws Exception {
            var workerDir = Files.createDirectories(temp.resolve("w2-g1"));

            var dataHome = OpencodeData.prepare(workerDir, temp.resolve("missing.json"));

            assertTrue(Files.isDirectory(dataHome.resolve("opencode")));
            assertFalse(Files.exists(dataHome.resolve("opencode/auth.json")));
        }

        @Test
        @DisplayName("a failing removal reports false")
        void removalFails() throws Exception {
            var dataHome = temp.resolve("xdg-data");
            Files.createDirectories(dataHome.resolve("opencode/auth.json/occupied"));

            assertFalse(OpencodeData.removeLogin(dataHome));
        }
    }

    @Nested
    @DisplayName("write set and switch")
    class WriteSet {

        @Test
        @DisplayName("drops only the shared data and the configuration directory")
        void perWorkerWriteSet() {
            var home = Path.of("/home/op");
            var writeSet = List.of(Path.of("/w/w1-g1"), home.resolve(".local/share/opencode"),
                    home.resolve(".local/state/opencode"), home.resolve(".cache/opencode"),
                    home.resolve(".config/opencode"), Path.of("/tmp"));

            assertEquals(List.of(Path.of("/w/w1-g1"), home.resolve(".local/state/opencode"),
                    home.resolve(".cache/opencode"), Path.of("/tmp")), OpencodeData.perWorkerWriteSet(writeSet, home));
        }

        @ParameterizedTest(name = "{0}, shared {1}: {2}")
        @CsvSource({"opencode, false, true", "opencode, true, false", "claude, false, false", "codex, false, false"})
        @DisplayName("applies to OpenCode unless shared data is requested")
        void applies(String harness, boolean shared, boolean expected) {
            assertEquals(expected, OpencodeData.applies(Harness.of(harness), shared));
        }
    }

    @Nested
    @DisplayName("stale logins")
    class StaleLogins {

        private Path copy(String workspace, String worker) throws Exception {
            var login = Files.writeString(temp.resolve("auth.json"), "secret");
            var workerDir = Files.createDirectories(temp.resolve("root").resolve(workspace).resolve(worker));
            return OpencodeData.prepare(workerDir, login).resolve("opencode/auth.json");
        }

        @Test
        @DisplayName("deletes the copies of a workspace whose driver no longer runs, or is not recorded")
        void deletesOrphans() throws Exception {
            var dead = copy("dead", "w1-g1");
            var deadToo = copy("dead", "w2-g4");
            var unmarked = copy("unmarked", "w1-g1");
            var garbled = copy("garbled", "w1-g1");
            var live = copy("live", "w1-g1");
            OpencodeData.markOwner(temp.resolve("root/dead"), 41);
            OpencodeData.markOwner(temp.resolve("root/live"), 42);
            Files.writeString(temp.resolve("root/garbled").resolve(OpencodeData.OWNER), "no pid");

            int removed = OpencodeData.removeStaleLogins(temp.resolve("root"), pid -> pid == 42);

            assertEquals(4, removed);
            assertFalse(Files.exists(dead));
            assertFalse(Files.exists(deadToo));
            assertFalse(Files.exists(unmarked));
            assertFalse(Files.exists(garbled));
            assertTrue(Files.exists(live));
            assertTrue(Files.isDirectory(dead.getParent()), "only the login is deleted");
        }

        @Test
        @DisplayName("a workspace without login copies is left alone")
        void nothingToDelete() throws Exception {
            Files.createDirectories(temp.resolve("root/empty/w1-g1"));

            assertEquals(0, OpencodeData.removeStaleLogins(temp.resolve("root"), _ -> false));
        }
    }
}
