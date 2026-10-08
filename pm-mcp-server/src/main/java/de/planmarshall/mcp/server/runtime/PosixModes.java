/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.runtime;

import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;


import lombok.experimental.UtilityClass;

/**
 * The two modes of everything the runtime creates under {@code <PM_MCP_BASE>}: {@code 0700} for directories,
 * {@code 0600} for files (PM-SEC-4).
 *
 * @since 0.1
 */
@UtilityClass
public final class PosixModes {

    /** {@code rwx------}. */
    public static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");

    /** {@code rw-------}. */
    public static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

    /**
     * @return the creation attribute for a directory with mode {@code 0700}
     */
    public static FileAttribute<Set<PosixFilePermission>> directoryAttribute() {
        return PosixFilePermissions.asFileAttribute(DIRECTORY);
    }

    /**
     * @return the creation attribute for a file with mode {@code 0600}
     */
    public static FileAttribute<Set<PosixFilePermission>> fileAttribute() {
        return PosixFilePermissions.asFileAttribute(FILE);
    }

    /**
     * @param permissions a permission set
     * @return the octal notation, for example {@code 0755}
     */
    public static String octal(Set<PosixFilePermission> permissions) {
        int mode = 0;
        for (var permission : permissions) {
            mode |= 1 << (8 - permission.ordinal());
        }
        return "0%o".formatted(mode);
    }
}
