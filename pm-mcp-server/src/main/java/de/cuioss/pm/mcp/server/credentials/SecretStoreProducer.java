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

import de.cuioss.pm.mcp.server.PmMcpLogMessages;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the one active {@link SecretStore}, selected when the runtime starts.
 */
@ApplicationScoped
class SecretStoreProducer {

    private static final CuiLogger LOGGER = new CuiLogger(SecretStoreProducer.class);

    @Produces
    @Singleton
    @Startup
    SecretStore secretStore() {
        var selection = SecretStoreSelector.select();
        selection.fallbackReason().ifPresent(reason -> LOGGER.warn(PmMcpLogMessages.WARN.KEYRING_UNAVAILABLE, reason));
        LOGGER.info(PmMcpLogMessages.INFO.CREDENTIAL_STORE_SELECTED, selection.store().name(), selection.service());
        return selection.store();
    }
}
