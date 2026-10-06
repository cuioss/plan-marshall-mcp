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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.pm.mcp.server.test.DaemonProcess;
import de.cuioss.pm.mcp.server.test.TestBases;
import de.cuioss.pm.mcp.server.test.TestRuntime;
import de.cuioss.pm.mcp.server.test.UdsHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gates 4 and 5 from the packaged daemon (runner JAR or native {@code pm-mcpd}, started as a process with its own
 * short {@code PM_MCP_BASE}, so the entries live under an instance-qualified service name): a put, get, replace
 * and delete of one credential through the credential resource of the local API, served by the backend the
 * daemon selected at start. Records {@code target/verification-results/gate4-keychain-<mode>.json} (macOS) or
 * {@code gate5-secret-service-<mode>.json} (Linux); the times include the request over the socket.
 */
@DisplayName("OS keyring from the packaged daemon")
class KeyringIT {

    private static final String KEY = "pm-mcp-keyring-it";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Path base;

    @BeforeEach
    void prepare() {
        base = TestBases.create("pmk");
    }

    @AfterEach
    void cleanUp() {
        TestBases.delete(base);
    }

    private static UdsHttp.Response credential(DaemonProcess daemon, String method, String value) throws IOException {
        var headers = TestRuntime.bearer(daemon.token());
        headers.put("Content-Type", "application/json");
        return UdsHttp.request(daemon.paths().socket(), method, "/api/v1/credentials/" + KEY, headers,
                value == null ? null : JSON.writeValueAsString(JSON.createObjectNode().put("value", value)));
    }

    @Test
    @DisplayName("put, get, replace and delete through the selected backend")
    void roundTrip() throws Exception {
        var macos = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        var secret = UUID.randomUUID().toString();
        var values = new LinkedHashMap<String, Object>();
        values.put("mode", DaemonProcess.isNative() ? "native" : "jvm");
        try (var daemon = DaemonProcess.startReady(base)) {
            try {
                long start = System.nanoTime();
                var put = credential(daemon, "PUT", secret);
                values.put("put_us", (System.nanoTime() - start) / 1000);
                start = System.nanoTime();
                var read = credential(daemon, "GET", null);
                values.put("get_us", (System.nanoTime() - start) / 1000);
                var replace = credential(daemon, "PUT", secret + "-2");
                var replaced = credential(daemon, "GET", null);
                start = System.nanoTime();
                var delete = credential(daemon, "DELETE", null);
                values.put("delete_us", (System.nanoTime() - start) / 1000);
                var gone = credential(daemon, "GET", null);

                assertEquals(204, put.status(), put.body() + daemon.output());
                assertEquals(200, read.status(), read.body() + daemon.output());
                var entry = JSON.readTree(read.body());
                var store = entry.path("store").asText();
                values.put("store", store);
                boolean roundTrip = secret.equals(entry.path("value").asText()) && replace.status() == 204
                        && (secret + "-2").equals(JSON.readTree(replaced.body()).path("value").asText())
                        && delete.status() == 204 && gone.status() == 404;
                values.put("round_trip", roundTrip);
                var expectedStore = macos ? SecretStore.KEYCHAIN : SecretStore.SECRET_SERVICE;
                VerificationResults.write((macos ? "gate4-keychain-" : "gate5-secret-service-") + values.get("mode"),
                        values, roundTrip && expectedStore.equals(store));

                assertTrue(roundTrip, values + " " + replaced.body() + " " + gone.status());
                assertTrue(daemon.output().contains("service name '" + ServiceName.DEFAULT + "/"), daemon.output());
                if (macos) {
                    assertEquals(SecretStore.KEYCHAIN, store, daemon.output());
                }
            } finally {
                credential(daemon, "DELETE", null);
            }
        }
    }
}
