/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.test;

/**
 * The fixed secrets of the test registries ({@link TestRegistries}). The packaged daemon of the integration tests
 * has the empty product registries, so there both are unknown secrets.
 */
public final class TestSecrets {

    /** The job the job token is bound to. */
    public static final String JOB_ID = "j-test0001";

    /** The device the device secret is bound to. */
    public static final String DEVICE_ID = "d-test0001";

    /** The job token of the job {@value #JOB_ID}. */
    public static final String JOB_TOKEN = "job-token-for-tests-0123456789abcdef";

    /** The device secret of the device {@value #DEVICE_ID}. */
    public static final String DEVICE_SECRET = "pmw_device-secret-for-tests-0123456789";

    private TestSecrets() {
    }
}
