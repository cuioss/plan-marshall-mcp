/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.credentials;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;


import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one {@link SecretStore} bean is selected when the application starts. No test here writes
 * through the HTTP resource: on macOS that would reach the login keychain under the real service name.
 */
@QuarkusTest
@DisplayName("SecretStoreProducer in the application")
class SecretStoreProducerTest {

    @Inject
    SecretStore store;

    @Test
    @DisplayName("one SecretStore bean is selected at start")
    void storeBean() {
        assertTrue(List.of(SecretStore.KEYCHAIN, SecretStore.SECRET_SERVICE, SecretStore.FILE).contains(store.name()));
    }
}
