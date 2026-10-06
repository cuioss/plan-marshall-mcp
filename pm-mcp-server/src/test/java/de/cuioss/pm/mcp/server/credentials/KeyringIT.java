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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.pm.mcp.server.test.DaemonProcess;
import de.cuioss.pm.mcp.server.test.TestBases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gates 4 and 5 from the packaged daemon (runner JAR or native {@code pm-mcpd}, started as a process with its own
 * short {@code PM_MCP_BASE}): the spike hook {@code de.cuioss.pm.mcp.spike.keyring} runs a put, get, replace and
 * delete through the backend selected for a non-default machine root at start and writes its outcome; this test
 * checks it and records {@code target/verification-results/gate4-keychain-<mode>.json} (macOS) or
 * {@code gate5-secret-service-<mode>.json} (Linux).
 */
@DisplayName("OS keyring from the packaged daemon")
class KeyringIT {

    static final Path RESULT = Path.of("target", "keyring-it", "result.json").toAbsolutePath();
    static final Path KEYRING_BASE = Path.of("target", "keyring-it", "base").toAbsolutePath();

    private Path base;

    @BeforeEach
    void prepare() throws IOException {
        Files.createDirectories(KEYRING_BASE);
        Files.deleteIfExists(RESULT);
        base = TestBases.create("pmk");
    }

    @AfterEach
    void cleanUp() {
        TestBases.delete(base);
    }

    @Test
    @DisplayName("put, get, replace and delete through the selected backend")
    void roundTrip() throws Exception {
        try (var daemon = DaemonProcess.start(base, List.of("-Dpm.spike.keyring.result=" + RESULT,
                     "-Dpm.spike.keyring.base=" + KEYRING_BASE))) {
            daemon.awaitReady(Duration.ofSeconds(30));
            for (int i = 0; i < 100 && !Files.exists(RESULT); i++) {
                Thread.sleep(100);
            }
            assertTrue(Files.exists(RESULT), daemon.output());
        }
        var outcome = new ObjectMapper().readTree(RESULT.toFile());
        var macos = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        var expectedStore = macos ? SecretStore.KEYCHAIN : SecretStore.SECRET_SERVICE;
        var values = new LinkedHashMap<String, Object>();
        values.put("mode", DaemonProcess.isNative() ? "native" : "jvm");
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
