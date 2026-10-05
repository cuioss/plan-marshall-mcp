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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;


import de.cuioss.pm.mcp.server.credentials.dbus.DbusConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Gate 5 on the JVM: the Secret Service of the Linux session (gnome-keyring or KWallet) over the
 * JDK Unix domain socket channel, under a unique test service name. Skipped when no session bus
 * address with {@code unix:path=} exists or the default collection is locked.
 */
@EnabledOnOs(OS.LINUX)
@DisplayName("SecretServiceStore against the session keyring")
class SecretServiceIT {

    private final String service = "de.cuioss.pm-mcp.test/" + UUID.randomUUID();
    private final CredentialAccount global = CredentialAccount.global("github");
    private final CredentialAccount project = CredentialAccount.project("p1", "github");
    private SecretServiceStore store;

    @BeforeEach
    void connect() {
        var socket = DbusConnection.sessionBusSocket(System.getenv());
        assumeTrue(socket.isPresent(), "no unix:path session bus address");
        var candidate = new SecretServiceStore(socket.get(), Posix.getuid(), service, SecretStoreSelector.BUS_TIMEOUT);
        // A session bus without org.freedesktop.secrets (a CI runner) skips the test; the cleanup then has no store
        assumeTrue(candidate.available(), "Secret Service not reachable or default collection locked");
        store = candidate;
    }

    @AfterEach
    void cleanUp() {
        if (store != null) {
            store.delete(global);
            store.delete(project);
        }
    }

    @Test
    @DisplayName("put, get, replace, resolve and delete")
    void roundTrip() throws Exception {
        long start = System.nanoTime();
        store.put(global, "first");
        long putMicros = (System.nanoTime() - start) / 1000;
        start = System.nanoTime();
        var read = store.get(global);
        long getMicros = (System.nanoTime() - start) / 1000;
        store.put(global, "second");
        store.put(project, "project");

        assertEquals(Optional.of("first"), read);
        assertEquals(Optional.of("second"), store.get(global));
        assertEquals(Optional.of("project"), store.resolve("p1", "github"));
        assertEquals(Optional.of("second"), store.resolve("p2", "github"));
        assertTrue(store.delete(project));
        assertTrue(store.delete(global));
        assertFalse(store.delete(global));

        var values = new LinkedHashMap<String, Object>();
        values.put("put_us", putMicros);
        values.put("get_us", getMicros);
        values.put("round_trip", true);
        VerificationResults.write("gate5-secret-service-jvm-direct", values, true);
    }
}
