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

import java.util.Locale;

/**
 * The operating systems of the launcher (PM-TECH-4).
 */
enum Os {
    /** Linux: no-new-privileges, Landlock, process group. */
    LINUX("linux"),
    /** macOS: process group only. */
    MACOS("macos");

    private final String jsonName;

    Os(String jsonName) {
        this.jsonName = jsonName;
    }

    /** @return the name in the probe JSON */
    String jsonName() {
        return jsonName;
    }

    /**
     * @param osName the value of {@code os.name}
     * @return the operating system; anything not Linux is treated as macOS (no confinement)
     */
    static Os of(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("linux") ? LINUX : MACOS;
    }
}
