/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike;

import de.cuioss.pm.mcp.server.tools.CoreTools;

/**
 * Tells the server's core tools apart from the stub's tools of the same name, by their description.
 */
final class CoreToolsAccess {

    private CoreToolsAccess() {
    }

    static boolean isCore(String name, String description) {
        return CoreTools.NAMES.contains(name) && CoreTools.description(name).equals(description);
    }
}
