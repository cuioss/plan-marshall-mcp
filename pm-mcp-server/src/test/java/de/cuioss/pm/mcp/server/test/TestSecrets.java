/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.test;

/**
 * The fixed secrets of the spike registries used by the tests.
 */
public final class TestSecrets {

    /** The job token of the spike job {@code j-spike0001}. */
    public static final String JOB_TOKEN = "job-token-for-tests-0123456789abcdef";

    /** The device secret of the spike device {@code d-spike0001}. */
    public static final String DEVICE_SECRET = "pmw_device-secret-for-tests-0123456789";

    private TestSecrets() {
    }
}
