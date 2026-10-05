/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.posix;

import java.lang.foreign.ValueLayout;

import de.cuioss.pm.api.MachinePaths;

/**
 * The platform constants and C type sizes the detached spawn needs, per supported C library. Every
 * value was read from the system headers and checked with a compiled probe printing the macros and
 * {@code sizeof} values:
 * <ul>
 * <li><b>macOS</b> (SDK {@code MacOSX.sdk}, {@code <sys/spawn.h>}, {@code <spawn.h>},
 * {@code <sys/fcntl.h>}, arm64): {@code POSIX_SPAWN_SETSID 0x0400},
 * {@code POSIX_SPAWN_CLOEXEC_DEFAULT 0x4000}; {@code posix_spawnattr_t} and
 * {@code posix_spawn_file_actions_t} are {@code void *} (8 bytes) that {@code *_init} allocates;
 * {@code sigset_t} is a 4-byte {@code __uint32_t}; {@code mode_t} is {@code __uint16_t};
 * {@code O_WRONLY 0x1}, {@code O_CREAT 0x200}, {@code O_APPEND 0x8}.</li>
 * <li><b>Linux glibc</b> (glibc 2.41, {@code <spawn.h>}, {@code <fcntl.h>}, identical on x86_64 and
 * aarch64): {@code POSIX_SPAWN_SETSID 0x80} (a GNU extension, {@code __USE_GNU}, glibc 2.26 or
 * later); {@code posix_spawnattr_t} is a 336-byte and {@code posix_spawn_file_actions_t} an 80-byte
 * structure the caller provides; {@code sigset_t} is 128 bytes; {@code mode_t} is 32 bits;
 * {@code O_WRONLY 0x1}, {@code O_CREAT 0x40}, {@code O_APPEND 0x400}. glibc has no
 * {@code POSIX_SPAWN_CLOEXEC_DEFAULT}; {@code posix_spawn_file_actions_addclosefrom_np} (glibc
 * 2.34 or later) closes the inherited descriptors instead.</li>
 * </ul>
 * {@code POSIX_SPAWN_SETSIGDEF 0x04} and {@code POSIX_SPAWN_SETSIGMASK 0x08}, and the signal numbers
 * used here, are the same on both.
 */
public enum PosixPlatform {

    /** macOS (libSystem). */
    MACOS((short) 0x0400, (short) 0x4000, 8, 8, 4, ValueLayout.JAVA_SHORT, 0x200, 0x8, false),

    /** Linux with glibc. */
    LINUX((short) 0x80, (short) 0, 336, 80, 128, ValueLayout.JAVA_INT, 0x40, 0x400, true);

    /** {@code POSIX_SPAWN_SETSIGDEF}. */
    public static final short SPAWN_SETSIGDEF = 0x04;
    /** {@code POSIX_SPAWN_SETSIGMASK}. */
    public static final short SPAWN_SETSIGMASK = 0x08;
    /** {@code O_RDONLY}. */
    public static final int O_RDONLY = 0;
    /** {@code O_WRONLY}. */
    public static final int O_WRONLY = 0x1;
    /** {@code SIGHUP}, {@code SIGINT}, {@code SIGQUIT}, {@code SIGPIPE}, {@code SIGTERM}. */
    static final int[] DEFAULT_SIGNALS = {1, 2, 3, 13, 15};

    private final short setsid;
    private final short cloexecDefault;
    private final long spawnAttrSize;
    private final long fileActionsSize;
    private final long sigsetSize;
    private final ValueLayout modeLayout;
    private final int oCreat;
    private final int oAppend;
    private final boolean closeFromAction;

    PosixPlatform(short setsid, short cloexecDefault, long spawnAttrSize, long fileActionsSize, long sigsetSize,
            ValueLayout modeLayout, int oCreat, int oAppend, boolean closeFromAction) {
        this.setsid = setsid;
        this.cloexecDefault = cloexecDefault;
        this.spawnAttrSize = spawnAttrSize;
        this.fileActionsSize = fileActionsSize;
        this.sigsetSize = sigsetSize;
        this.modeLayout = modeLayout;
        this.oCreat = oCreat;
        this.oAppend = oAppend;
        this.closeFromAction = closeFromAction;
    }

    /**
     * @param os the operating system
     * @return its platform constants
     */
    public static PosixPlatform of(MachinePaths.Os os) {
        return os == MachinePaths.Os.MACOS ? MACOS : LINUX;
    }

    /** @return the platform of the running process */
    public static PosixPlatform current() {
        return of(MachinePaths.Os.current());
    }

    /** @return {@code POSIX_SPAWN_SETSID} */
    public short setsidFlag() {
        return setsid;
    }

    /** @return {@code POSIX_SPAWN_CLOEXEC_DEFAULT}, {@code 0} where the platform has none */
    public short cloexecDefaultFlag() {
        return cloexecDefault;
    }

    /**
     * The flags of a detached spawn: a new session, an empty signal mask, the default disposition of
     * the termination signals, and no inherited descriptor where the platform supports it.
     *
     * @return the {@code posix_spawnattr_setflags} value
     */
    public short detachedSpawnFlags() {
        return (short) (setsid | SPAWN_SETSIGMASK | SPAWN_SETSIGDEF | cloexecDefault);
    }

    /** @return {@code O_WRONLY | O_CREAT | O_APPEND} */
    public int appendFlags() {
        return O_WRONLY | oCreat | oAppend;
    }

    /** @return bytes to allocate for {@code posix_spawnattr_t} */
    public long spawnAttrSize() {
        return spawnAttrSize;
    }

    /** @return bytes to allocate for {@code posix_spawn_file_actions_t} */
    public long fileActionsSize() {
        return fileActionsSize;
    }

    /** @return bytes to allocate for {@code sigset_t} */
    public long sigsetSize() {
        return sigsetSize;
    }

    /** @return the C layout of {@code mode_t} */
    public ValueLayout modeLayout() {
        return modeLayout;
    }

    /** @return {@code true} if inherited descriptors are closed by a {@code closefrom} file action */
    public boolean usesCloseFromAction() {
        return closeFromAction;
    }
}
