/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.security;

import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Default registries")
class EmptyRegistriesTest {

    private final EmptyRegistries registries = new EmptyRegistries();

    @Test
    @DisplayName("no job token resolves")
    void shouldResolveNoJob() {
        var hash = Secrets.sha256("job-token");

        var job = registries.noJobs().resolve(hash);

        assertTrue(job.isEmpty());
    }

    @Test
    @DisplayName("no device secret resolves")
    void shouldResolveNoDevice() {
        var hash = Secrets.sha256(DeviceRegistry.SECRET_PREFIX + "secret");

        var device = registries.noDevices().resolve(hash);

        assertTrue(device.isEmpty());
    }
}
