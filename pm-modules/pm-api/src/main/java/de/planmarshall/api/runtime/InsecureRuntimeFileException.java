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

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;

/**
 * The client check of the runtime token refused a file: {@code run/} or {@code run/runtime.token}
 * is a symbolic link, has another owner than the effective user, or a mode other than {@code 0700}
 * and {@code 0600}. Nothing was sent.
 */
public class InsecureRuntimeFileException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The refused path. */
    private final transient Path path;

    /**
     * @param path   the refused path
     * @param reason why it was refused
     */
    public InsecureRuntimeFileException(Path path, String reason) {
        super("Refusing " + path + ": " + reason);
        this.path = path;
    }

    /** @return the refused path */
    public Path path() {
        return path;
    }
}
