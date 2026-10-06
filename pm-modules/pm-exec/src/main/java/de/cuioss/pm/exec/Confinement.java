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

/**
 * The confinement applied to a job, in the terms of the job record ({@code confinement},
 * {@code landlock_abi}).
 *
 * @param state       {@code confined}, {@code partial(abi=1)} or {@code unavailable}
 * @param landlockAbi the ABI of the applied ruleset, {@code 0} when none was applied
 * @param noNewPrivs  whether {@code PR_SET_NO_NEW_PRIVS} is in effect
 */
record Confinement(String state, int landlockAbi, boolean noNewPrivs) {

    /** A ruleset of ABI 2 or higher was applied. */
    static final String CONFINED = "confined";
    /** No Landlock, or a platform without it (macOS). */
    static final String UNAVAILABLE = "unavailable";

    /**
     * Classifies a kernel's Landlock ABI.
     *
     * @param abi        the ABI the kernel offers, {@code 0} if none
     * @param noNewPrivs whether {@code PR_SET_NO_NEW_PRIVS} is in effect
     * @return the confinement a launch applies with it
     */
    static Confinement forAbi(int abi, boolean noNewPrivs) {
        if (abi >= Landlock.MIN_CONFINING_ABI) {
            return new Confinement(CONFINED, abi, noNewPrivs);
        }
        if (abi == 1) {
            return new Confinement("partial(abi=1)", 0, noNewPrivs);
        }
        return new Confinement(UNAVAILABLE, 0, noNewPrivs);
    }

    /** @return whether a Landlock ruleset is applied */
    boolean isConfined() {
        return CONFINED.equals(state);
    }

    /** @return the JSON value of {@code landlock_abi}: the ABI when confined, else {@code null} */
    String abiJson() {
        return isConfined() ? Integer.toString(landlockAbi) : "null";
    }
}
