/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.ci;

import java.util.Optional;

/**
 * Resolves the bearer token per request (a stored secret is never cached by the HTTP base).
 *
 * @since 0.1
 */
@FunctionalInterface
public interface TokenSource {

    /** A source without token, for unauthenticated requests. */
    TokenSource NONE = Optional::empty;

    /**
     * @return the token for the next request, if any
     */
    Optional<String> token();
}
