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

import java.util.Optional;


import de.cuioss.pm.mcp.server.security.DeviceRegistry;
import de.cuioss.pm.mcp.server.security.JobTokenRegistry;
import de.cuioss.pm.mcp.server.security.Secrets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Test registries of the {@code @QuarkusTest} application: one job token ({@link TestSecrets#JOB_TOKEN}, job
 * {@link TestSecrets#JOB_ID}) and one device secret ({@link TestSecrets#DEVICE_SECRET}, device
 * {@link TestSecrets#DEVICE_ID}). They replace the empty default registries of the product, which resolve nothing
 * until the job records and the web device store exist.
 */
@ApplicationScoped
public class TestRegistries {

    private static final byte[] JOB_TOKEN_HASH = Secrets.sha256(TestSecrets.JOB_TOKEN);
    private static final byte[] DEVICE_SECRET_HASH = Secrets.sha256(TestSecrets.DEVICE_SECRET);

    @Produces
    @ApplicationScoped
    JobTokenRegistry jobTokens() {
        return hash -> Secrets.constantTimeEquals(JOB_TOKEN_HASH, hash)
                ? Optional.of(new JobTokenRegistry.JobBinding(TestSecrets.JOB_ID))
                : Optional.empty();
    }

    @Produces
    @ApplicationScoped
    DeviceRegistry devices() {
        return hash -> Secrets.constantTimeEquals(DEVICE_SECRET_HASH, hash)
                ? Optional.of(TestSecrets.DEVICE_ID)
                : Optional.empty();
    }
}
