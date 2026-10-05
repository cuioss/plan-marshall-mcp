/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.git;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of a git contract operation: its outcome, the value on {@link GitOutcome#OK}, and a
 * detail for every other outcome (the failing step, the refused feature, the rejected ref).
 *
 * @param outcome the outcome
 * @param value   the value, present exactly for {@link GitOutcome#OK}
 * @param detail  the detail, empty for {@link GitOutcome#OK}
 * @param <V>     the value type
 * @since 0.1
 */
public record GitResult<V>(GitOutcome outcome, Optional<V> value, String detail) {

    /**
     * @param outcome the outcome
     * @param value   the value
     * @param detail  the detail
     */
    public GitResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(detail, "detail");
    }

    /**
     * @param value the value
     * @param <V>   the value type
     * @return an {@link GitOutcome#OK} result
     */
    public static <V> GitResult<V> ok(V value) {
        return new GitResult<>(GitOutcome.OK, Optional.of(value), "");
    }

    /**
     * @param outcome the non-OK outcome
     * @param detail  the detail
     * @param <V>     the value type
     * @return a result without value
     */
    public static <V> GitResult<V> of(GitOutcome outcome, String detail) {
        return new GitResult<>(outcome, Optional.empty(), detail);
    }

    /**
     * @return whether the outcome is {@link GitOutcome#OK}
     */
    public boolean isOk() {
        return outcome == GitOutcome.OK;
    }
}
