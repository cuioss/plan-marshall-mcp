/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.security;

import java.util.Optional;

/**
 * Resolves a paired browser's device secret by its SHA-256 to the device id, checked on every request without
 * a cache (the web device store).
 *
 * @since 0.1
 */
public interface DeviceRegistry {

    /** Prefix of every device secret. */
    String SECRET_PREFIX = "pmw_";

    /**
     * @param secretSha256 the SHA-256 of the presented secret
     * @return the device id, or empty for an unknown, revoked or expired secret
     */
    Optional<String> resolve(byte[] secretSha256);
}
