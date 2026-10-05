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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gates 4 and 5 from the packaged application (runner JAR or native {@code pm-mcpd}): the spike
 * hook {@code de.cuioss.pm.mcp.spike.keyring} runs a put, get, replace and delete through the
 * selected backend at start and writes its outcome; this test checks it and records
 * {@code target/verification-results/gate4-keychain-<mode>.json} (macOS) or
 * {@code gate5-secret-service-<mode>.json} (Linux).
 */
@QuarkusIntegrationTest
@TestProfile(KeyringIT.Profile.class)
@DisplayName("OS keyring from the packaged application")
class KeyringIT {

    static final Path RESULT = Path.of("target", "keyring-it", "result.json").toAbsolutePath();
    static final Path BASE = Path.of("target", "keyring-it", "base").toAbsolutePath();

    /** Activates the spike hook. */
    public static class Profile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Files.createDirectories(BASE);
                Files.deleteIfExists(RESULT);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Map.of("pm.spike.keyring.result", RESULT.toString(), "pm.spike.keyring.base", BASE.toString());
        }
    }

    @Test
    @DisplayName("put, get, replace and delete through the selected backend")
    void roundTrip() throws Exception {
        for (int i = 0; i < 100 && !Files.exists(RESULT); i++) {
            Thread.sleep(100);
        }
        var outcome = new ObjectMapper().readTree(RESULT.toFile());
        var macos = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        var expectedStore = macos ? SecretStore.KEYCHAIN : SecretStore.SECRET_SERVICE;
        var values = new LinkedHashMap<String, Object>();
        values.put("mode", VerificationResults.packagedMode());
        values.put("store", outcome.path("store").asText());
        values.put("service", outcome.path("service").asText());
        values.put("fallback_reason", outcome.path("fallback_reason").asText(null));
        values.put("round_trip", outcome.path("round_trip").asBoolean());
        values.put("put_us", outcome.path("put_us").asLong());
        values.put("get_us", outcome.path("get_us").asLong());
        values.put("delete_us", outcome.path("delete_us").asLong());
        values.put("error", outcome.path("error").asText(null));
        boolean pass = outcome.path("round_trip").asBoolean() && expectedStore.equals(outcome.path("store").asText());
        VerificationResults.write((macos ? "gate4-keychain-" : "gate5-secret-service-") + values.get("mode"), values,
                pass);

        assertTrue(outcome.path("round_trip").asBoolean(), outcome.toString());
        assertTrue(outcome.path("service").asText().startsWith(ServiceName.DEFAULT + "/"), outcome.toString());
        if (macos) {
            assertEquals(SecretStore.KEYCHAIN, outcome.path("store").asText(), outcome.toString());
        }
    }
}
