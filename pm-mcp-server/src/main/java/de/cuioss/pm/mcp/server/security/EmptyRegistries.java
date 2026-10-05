/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.security;

import java.util.Optional;


import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Default registries until the job records and the web device store exist: no job token and no device secret
 * resolves. Any other bean of the registry type replaces them.
 *
 * @since 0.1
 */
@ApplicationScoped
public class EmptyRegistries {

    @Produces
    @DefaultBean
    @ApplicationScoped
    JobTokenRegistry noJobs() {
        return _ -> Optional.empty();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    DeviceRegistry noDevices() {
        return _ -> Optional.empty();
    }
}
