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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Real login-keychain items under a unique test service name, created, read, replaced and deleted
 * by this process, so the creator ACL never prompts. Gate 4 on the JVM.
 */
@EnabledOnOs(OS.MAC)
@DisplayName("KeychainSecretStore against the macOS login keychain")
class KeychainSecretStoreTest {

    private final String service = "de.cuioss.pm-mcp.test/" + UUID.randomUUID();
    private final KeychainSecretStore store = new KeychainSecretStore(service);
    private final CredentialAccount global = CredentialAccount.global("github");
    private final CredentialAccount project = CredentialAccount.project("p1", "github");

    @AfterEach
    void cleanUp() {
        store.delete(global);
        store.delete(project);
    }

    @Test
    @DisplayName("put, get, replace and delete a generic-password item")
    void roundTrip() {
        assertEquals(Optional.empty(), store.get(global));

        store.put(global, "first-s3cr€t");
        assertEquals(Optional.of("first-s3cr€t"), store.get(global));
        store.put(global, "second");
        assertEquals(Optional.of("second"), store.get(global));

        assertTrue(store.delete(global));
        assertFalse(store.delete(global));
        assertEquals(Optional.empty(), store.get(global));
        assertEquals(SecretStore.KEYCHAIN, store.name());
    }

    @Test
    @DisplayName("project and global entries are separate accounts, the project one resolves first")
    void projectAccount() {
        store.put(global, "global");
        store.put(project, "project");

        assertEquals(Optional.of("project"), store.resolve("p1", "github"));
        assertEquals(Optional.of("global"), store.resolve("p2", "github"));
    }

    @Test
    @DisplayName("finding: the data-protection keychain refuses an unentitled binary (errSecMissingEntitlement)")
    void dataProtectionKeychainNeedsEntitlement() {
        var dataProtection = new KeychainSecretStore(service, true);

        var e = assertThrows(KeychainSecretStore.KeychainException.class, () -> dataProtection.put(global, "x"));
        assertEquals(MacSecurity.MISSING_ENTITLEMENT, e.status());
        assertEquals(SecretStoreException.Reason.FAILED, e.reason());
    }
}
