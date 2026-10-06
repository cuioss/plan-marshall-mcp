/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Optional;

import de.cuioss.pm.provider.ci.Json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("GitHubAppJwt")
class GitHubAppJwtTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    @DisplayName("signs RS256 verifiable with the public key, with the specified window and issuer")
    void signsAndVerifies() throws Exception {
        String jwt = GitHubAppJwt.sign("Iv23liClientId", TestKeys.PKCS8_PEM, NOW);

        Object claims = Json.parse(TestKeys.verifiedClaims(jwt, TestKeys.PAIR.getPublic()));
        Object header = Json.parse(TestKeys.header(jwt));

        assertEquals(Optional.of("RS256"), Json.string(header, "alg"));
        assertEquals(Optional.of("JWT"), Json.string(header, "typ"));
        assertEquals(Optional.of(String.valueOf(NOW.getEpochSecond() - 60)), Json.string(claims, "iat"));
        assertEquals(Optional.of(String.valueOf(NOW.getEpochSecond() + 600)), Json.string(claims, "exp"));
        assertEquals(Optional.of("Iv23liClientId"), Json.string(claims, "iss"));
        assertFalse(jwt.contains("="), "base64url without padding");
    }

    @Test
    @DisplayName("is rejected by another key and differs per signing instant")
    void otherKeyRejects() throws Exception {
        String jwt = GitHubAppJwt.sign("id", TestKeys.PKCS8_PEM, NOW);
        var other = KeyPairGenerator.getInstance("RSA");
        other.initialize(2048);
        var otherKey = other.generateKeyPair().getPublic();

        assertThrows(GeneralSecurityException.class, () -> TestKeys.verifiedClaims(jwt, otherKey));
        assertNotEquals(jwt, GitHubAppJwt.sign("id", TestKeys.PKCS8_PEM, NOW.plusSeconds(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-----BEGIN RSA PRIVATE KEY-----\nMIIB\n-----END RSA PRIVATE KEY-----",
            "-----BEGIN PRIVATE KEY-----\n!!not base64!!\n-----END PRIVATE KEY-----",
            "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----",
            "not a pem"
    })
    @DisplayName("refuses keys that are not PEM-encoded PKCS#8 RSA, without echoing them")
    void refusesKeys(String pem) {
        var refusal = assertThrows(GitHubAppJwt.JwtSigningException.class, () -> GitHubAppJwt.sign("id", pem, NOW));

        assertFalse(refusal.getMessage().contains("MIIB"));
        assertTrue(refusal.getMessage().length() < 120);
    }
}
