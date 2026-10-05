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

import java.util.Map;


import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profile with the spike verification tools and the fixed spike job token and device secret.
 */
public class SpikeVerifyProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("pm.spike.verify", "true", "pm.spike.job-token", TestSecrets.JOB_TOKEN,
                "pm.spike.device-secret", TestSecrets.DEVICE_SECRET);
    }
}
