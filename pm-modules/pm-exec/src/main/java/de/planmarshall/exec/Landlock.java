/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.exec;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import lombok.experimental.UtilityClass;

/**
 * Landlock constants, access masks per ABI, and the two kernel structures (linux/landlock.h).
 * <p>
 * The syscall numbers are the generic ones, identical on {@code x86_64}
 * (arch/x86/entry/syscalls/syscall_64.tbl) and {@code aarch64} (include/uapi/asm-generic/unistd.h):
 * 444, 445 and 446.
 */
@UtilityClass
class Landlock {

    /** {@code __NR_landlock_create_ruleset}. */
    static final long SYS_CREATE_RULESET = 444;
    /** {@code __NR_landlock_add_rule}. */
    static final long SYS_ADD_RULE = 445;
    /** {@code __NR_landlock_restrict_self}. */
    static final long SYS_RESTRICT_SELF = 446;

    /** {@code LANDLOCK_CREATE_RULESET_VERSION}: returns the highest supported ABI. */
    static final long CREATE_RULESET_VERSION = 1L;
    /** {@code LANDLOCK_RULE_PATH_BENEATH}. */
    static final long RULE_PATH_BENEATH = 1L;

    /** {@code LANDLOCK_ACCESS_FS_EXECUTE}. */
    static final long EXECUTE = 1L;
    /** {@code LANDLOCK_ACCESS_FS_WRITE_FILE}. */
    static final long WRITE_FILE = 1L << 1;
    /** {@code LANDLOCK_ACCESS_FS_READ_FILE}. */
    static final long READ_FILE = 1L << 2;
    /** {@code LANDLOCK_ACCESS_FS_READ_DIR}. */
    static final long READ_DIR = 1L << 3;
    /** {@code LANDLOCK_ACCESS_FS_REFER}, ABI 2. */
    static final long REFER = 1L << 13;
    /** {@code LANDLOCK_ACCESS_FS_TRUNCATE}, ABI 3. */
    static final long TRUNCATE = 1L << 14;
    /** {@code LANDLOCK_ACCESS_FS_IOCTL_DEV}, ABI 5. */
    static final long IOCTL_DEV = 1L << 15;
    /**
     * {@code LANDLOCK_ACCESS_FS_RESOLVE_UNIX}, ABI 9: {@code connect(2)} to (and {@code sendmsg(2)} with an
     * explicit address of) a pathname Unix socket created outside the job's Landlock domain. Below ABI 9
     * Landlock does not govern pathname socket connects at all.
     */
    static final long RESOLVE_UNIX = 1L << 16;

    /** The 13 rights of ABI 1: {@code EXECUTE} up to {@code MAKE_SYM}. */
    static final long ABI1_RIGHTS = (1L << 13) - 1;

    /** Rights a rule on a non-directory may carry (the kernel's {@code ACCESS_FILE}). */
    static final long FILE_RIGHTS = EXECUTE | WRITE_FILE | READ_FILE | TRUNCATE | IOCTL_DEV | RESOLVE_UNIX;

    /**
     * Rights of the read set: read, execute and, from ABI 9, connecting to the pathname Unix sockets in it. A
     * socket outside the read and write sets (below {@code <PM_MCP_BASE>}) is then unreachable unless a
     * {@code --read} rule names it.
     */
    static final long READ_RIGHTS = EXECUTE | READ_FILE | READ_DIR | RESOLVE_UNIX;

    /** Lowest ABI that confines a job (ABI 1 lacks {@code REFER}, so cross-directory renames fail). */
    static final int MIN_CONFINING_ABI = 2;

    /**
     * {@code struct landlock_ruleset_attr}; only {@code handled_access_fs} is passed (size 8), so
     * network and scope rights stay unhandled whatever the ABI.
     */
    static final StructLayout RULESET_ATTR = MemoryLayout.structLayout(JAVA_LONG.withName("handled_access_fs"));

    /** {@code struct landlock_path_beneath_attr}, packed: 12 bytes, {@code parent_fd} at offset 8. */
    static final StructLayout PATH_BENEATH_ATTR = MemoryLayout.structLayout(
            JAVA_LONG.withName("allowed_access"),
            JAVA_INT.withName("parent_fd"));

    /**
     * The filesystem rights the given ABI handles; a ruleset handles all of them, so every right not
     * granted by a rule is denied.
     *
     * @param abi the Landlock ABI, at least 1
     * @return the {@code handled_access_fs} mask
     */
    static long handledAccess(int abi) {
        long rights = ABI1_RIGHTS;
        if (abi >= 2) {
            rights |= REFER;
        }
        if (abi >= 3) {
            rights |= TRUNCATE;
        }
        if (abi >= 5) {
            rights |= IOCTL_DEV;
        }
        if (abi >= 9) {
            rights |= RESOLVE_UNIX;
        }
        return rights;
    }

    /**
     * @param abi         the Landlock ABI
     * @param isDirectory whether the rule's path is a directory
     * @return the rights of a read-set rule
     */
    static long readAccess(int abi, boolean isDirectory) {
        return restrict(READ_RIGHTS & handledAccess(abi), isDirectory);
    }

    /**
     * @param abi         the Landlock ABI
     * @param isDirectory whether the rule's path is a directory
     * @return the rights of a write-set rule: every handled right
     */
    static long writeAccess(int abi, boolean isDirectory) {
        return restrict(handledAccess(abi), isDirectory);
    }

    private static long restrict(long rights, boolean isDirectory) {
        return isDirectory ? rights : rights & FILE_RIGHTS;
    }

    /**
     * @param arena             the arena to allocate in
     * @param handledAccessFs   the handled rights
     * @return a filled {@code landlock_ruleset_attr}
     */
    static MemorySegment rulesetAttr(Arena arena, long handledAccessFs) {
        var segment = arena.allocate(RULESET_ATTR);
        segment.set(JAVA_LONG, 0, handledAccessFs);
        return segment;
    }

    /**
     * @param arena         the arena to allocate in
     * @param allowedAccess the granted rights
     * @param parentFd      the {@code O_PATH} descriptor of the rule's path
     * @return a filled {@code landlock_path_beneath_attr}
     */
    static MemorySegment pathBeneathAttr(Arena arena, long allowedAccess, int parentFd) {
        var segment = arena.allocate(PATH_BENEATH_ATTR);
        segment.set(JAVA_LONG, 0, allowedAccess);
        segment.set(JAVA_INT, PATH_BENEATH_ATTR.byteOffset(MemoryLayout.PathElement.groupElement("parent_fd")),
                parentFd);
        return segment;
    }
}
