/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.verify.daemon;

import java.util.Optional;


import de.cuioss.pm.mcp.server.security.DeviceRegistry;
import de.cuioss.pm.mcp.server.security.JobTokenRegistry;
import de.cuioss.pm.mcp.server.security.Secrets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Fixed spike registries for the verification of gates 1, 10 and 15: one job token
 * ({@code pm.spike.job-token}, job {@value #JOB_ID}) and one device secret ({@code pm.spike.device-secret},
 * device {@value #DEVICE_ID}). Without the properties nothing resolves. They replace the empty default
 * registries until the job records and the web device store exist.
 */
@ApplicationScoped
public class SpikeRegistries {

    static final String JOB_ID = "j-spike0001";
    static final String DEVICE_ID = "d-spike0001";

    private final Optional<byte[]> jobTokenHash;
    private final Optional<byte[]> deviceSecretHash;

    SpikeRegistries(@ConfigProperty(name = "pm.spike.job-token") Optional<String> jobToken,
            @ConfigProperty(name = "pm.spike.device-secret") Optional<String> deviceSecret) {
        jobTokenHash = jobToken.map(Secrets::sha256);
        deviceSecretHash = deviceSecret.map(Secrets::sha256);
    }

    @Produces
    @ApplicationScoped
    JobTokenRegistry jobTokens() {
        return hash -> jobTokenHash.filter(expected -> Secrets.constantTimeEquals(expected, hash))
                .map(_ -> new JobTokenRegistry.JobBinding(JOB_ID));
    }

    @Produces
    @ApplicationScoped
    DeviceRegistry devices() {
        return hash -> deviceSecretHash.filter(expected -> Secrets.constantTimeEquals(expected, hash))
                .map(_ -> DEVICE_ID);
    }
}
