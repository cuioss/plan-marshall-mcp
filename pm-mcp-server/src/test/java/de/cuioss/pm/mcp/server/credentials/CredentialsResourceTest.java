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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;


import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resource methods against a real file store; the HTTP binding and the authentication are
 * supplied by the local API listener (another slice).
 */
@EnableTestLogger
@DisplayName("CredentialsResource: status codes and bodies")
class CredentialsResourceTest {

    @TempDir
    Path base;

    private CredentialsResource resource;

    @BeforeEach
    void create() {
        resource = new CredentialsResource(new FileSecretStore(base.resolve("credentials")));
    }

    @Test
    @DisplayName("PUT 204, GET 200 with value and store, DELETE 204, GET 404")
    void lifecycle() {
        assertEquals(204, resource.put("github", new CredentialsResource.CredentialValue("tok")).getStatus());

        var entry = resource.get("github");
        assertEquals(200, entry.getStatus());
        assertEquals(new CredentialsResource.CredentialEntry("tok", "file"), entry.getEntity());

        assertEquals(204, resource.delete("github").getStatus());
        assertEquals(204, resource.delete("github").getStatus());
        assertEquals(404, resource.get("github").getStatus());
    }

    @Test
    @DisplayName("PUT without value is 400")
    void missingValue() {
        assertEquals(400, resource.put("github", null).getStatus());
        assertEquals(400, resource.put("github", new CredentialsResource.CredentialValue(null)).getStatus());
    }

    @Test
    @DisplayName("an invalid key maps to 400 invalid_credential_key")
    void invalidKey() {
        var e = assertThrows(InvalidCredentialNameException.class, () -> resource.get("../x"));

        var response = resource.invalidName(e);
        assertEquals(400, response.getStatus());
        assertEquals(Map.of("error", "invalid_credential_key"), response.getEntity());
    }

    @Test
    @DisplayName("a locked keyring maps to 423, any other failure to 500, with the error code")
    void storeFailures() {
        var locked = resource.storeFailed(new SecretStoreException(SecretStoreException.Reason.LOCKED, "locked"));
        var invalid = resource.storeFailed(new SecretStoreException(SecretStoreException.Reason.INVALID, "bad"));

        assertEquals(423, locked.getStatus());
        assertEquals(Map.of("error", "credential_store_locked"), locked.getEntity());
        assertEquals(500, invalid.getStatus());
        assertEquals(Map.of("error", "credentials_file_invalid"), invalid.getEntity());
    }
}
