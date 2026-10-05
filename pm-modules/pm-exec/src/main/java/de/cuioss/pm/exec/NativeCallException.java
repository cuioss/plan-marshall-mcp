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

import java.io.Serial;

/**
 * A failed native call, with the {@code errno} it left.
 */
final class NativeCallException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The stage of the launch that failed, for the error line. */
    private final String stage;
    /** The {@code errno} of the failed call. */
    private final int errno;

    /**
     * @param stage   the stage, for example {@code landlock_add_rule}
     * @param errno   the {@code errno}
     * @param message what failed
     */
    NativeCallException(String stage, int errno, String message) {
        super(message);
        this.stage = stage;
        this.errno = errno;
    }

    /** @return the stage that failed */
    String stage() {
        return stage;
    }

    /** @return the {@code errno} of the failed call */
    int errno() {
        return errno;
    }
}
