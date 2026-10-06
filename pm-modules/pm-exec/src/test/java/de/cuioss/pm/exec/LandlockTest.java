/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.exec;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Landlock: masks and structures")
class LandlockTest {

    @Nested
    @DisplayName("access masks")
    class Masks {

        @ParameterizedTest(name = "ABI {0} handles 0x{1}")
        @CsvSource({"1, 1fff", "2, 3fff", "3, 7fff", "4, 7fff", "5, ffff", "6, ffff", "7, ffff"})
        @DisplayName("handled rights per ABI")
        void handled(int abi, String hex) {
            assertEquals(Long.parseLong(hex, 16), Landlock.handledAccess(abi));
        }

        @ParameterizedTest(name = "ABI {0}, directory {1}: read 0x{2}, write 0x{3}")
        @CsvSource({
                "2, true, d, 3fff",
                "2, false, 5, 7",
                "3, true, d, 7fff",
                "3, false, 5, 4007",
                "5, true, d, ffff",
                "5, false, 5, c007"})
        @DisplayName("rule rights for directories and files")
        void ruleRights(int abi, boolean directory, String read, String write) {
            assertEquals(Long.parseLong(read, 16), Landlock.readAccess(abi, directory));
            assertEquals(Long.parseLong(write, 16), Landlock.writeAccess(abi, directory));
        }

        @Test
        @DisplayName("bit values match linux/landlock.h")
        void bits() {
            assertEquals(1L, Landlock.EXECUTE);
            assertEquals(2L, Landlock.WRITE_FILE);
            assertEquals(4L, Landlock.READ_FILE);
            assertEquals(8L, Landlock.READ_DIR);
            assertEquals(0x2000L, Landlock.REFER);
            assertEquals(0x4000L, Landlock.TRUNCATE);
            assertEquals(0x8000L, Landlock.IOCTL_DEV);
        }

        @Test
        @DisplayName("syscall numbers are the generic 444..446")
        void syscallNumbers() {
            assertEquals(444L, Landlock.SYS_CREATE_RULESET);
            assertEquals(445L, Landlock.SYS_ADD_RULE);
            assertEquals(446L, Landlock.SYS_RESTRICT_SELF);
        }
    }

    @Nested
    @DisplayName("structures")
    class Structures {

        @Test
        @DisplayName("landlock_ruleset_attr is the 8-byte handled_access_fs prefix")
        void rulesetAttr() {
            assertEquals(8, Landlock.RULESET_ATTR.byteSize());
            try (var arena = Arena.ofConfined()) {
                var bytes = Landlock.rulesetAttr(arena, 0x3fffL).toArray(JAVA_BYTE);

                assertArrayEquals(new byte[]{(byte) 0xff, 0x3f, 0, 0, 0, 0, 0, 0}, bytes);
            }
        }

        @Test
        @DisplayName("landlock_path_beneath_attr is packed: 12 bytes, parent_fd at offset 8")
        void pathBeneathAttr() {
            assertEquals(12, Landlock.PATH_BENEATH_ATTR.byteSize());
            assertEquals(8, Landlock.PATH_BENEATH_ATTR.byteOffset(PathElement.groupElement("parent_fd")));
            try (var arena = Arena.ofConfined()) {
                var bytes = Landlock.pathBeneathAttr(arena, 0x400dL, 0x01020304).toArray(JAVA_BYTE);

                assertArrayEquals(new byte[]{0x0d, 0x40, 0, 0, 0, 0, 0, 0, 0x04, 0x03, 0x02, 0x01}, bytes);
            }
        }
    }
}
