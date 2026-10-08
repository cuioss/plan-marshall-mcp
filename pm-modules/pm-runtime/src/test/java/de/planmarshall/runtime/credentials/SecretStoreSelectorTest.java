/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import de.planmarshall.api.MachinePaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SecretStoreSelector: one active backend per machine")
class SecretStoreSelectorTest {

    @TempDir
    Path home;

    private final SecretStore keychain = new FileSecretStore(Path.of("/unused/keychain"));
    private final SecretStore secretService = new FileSecretStore(Path.of("/unused/secret-service"));
    private final List<String> probes = new ArrayList<>();

    private SecretStoreSelector.Keyrings keyrings(boolean keychainLoads, boolean busAnswers) {
        return new SecretStoreSelector.Keyrings() {

            @Override
            public SecretStore keychain(String service) {
                probes.add("keychain:" + service);
                if (!keychainLoads) {
                    throw new SecretStoreException(SecretStoreException.Reason.FAILED, "no framework");
                }
                return keychain;
            }

            @Override
            public Optional<SecretStore> secretService(Path socket, String service) {
                probes.add("secret-service:" + socket + ":" + service);
                return busAnswers ? Optional.of(secretService) : Optional.empty();
            }
        };
    }

    private MachinePaths paths(MachinePaths.Os os) {
        return new MachinePaths(home.resolve(".plan-marshall-mcp"), os);
    }

    @Test
    @DisplayName("macOS: the Keychain under the default service name")
    void macos() {
        var selection = SecretStoreSelector.select(paths(MachinePaths.Os.MACOS), Map.of(), home, keyrings(true, true));

        assertSame(keychain, selection.store());
        assertEquals("de.planmarshall.mcp", selection.service());
        assertTrue(selection.fallbackReason().isEmpty());
        assertEquals(List.of("keychain:de.planmarshall.mcp"), probes);
    }

    @Test
    @DisplayName("macOS without Security.framework: the file store with the reason")
    void macosFallback() {
        var selection = SecretStoreSelector.select(paths(MachinePaths.Os.MACOS), Map.of(), home, keyrings(false, true));

        var file = assertInstanceOf(FileSecretStore.class, selection.store());
        assertEquals(home.resolve(".plan-marshall-mcp/credentials/github.json"),
                file.path(CredentialAccount.global("github")));
        assertTrue(selection.fallbackReason().orElseThrow().contains("no framework"));
    }

    @Test
    @DisplayName("Linux with an answering session bus: the Secret Service")
    void linux() {
        var env = Map.of("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1/bus");

        var selection = SecretStoreSelector.select(paths(MachinePaths.Os.LINUX), env, home, keyrings(true, true));

        assertSame(secretService, selection.store());
        assertEquals(List.of("secret-service:/run/user/1/bus:de.planmarshall.mcp"), probes);
    }

    @Test
    @DisplayName("Linux whose bus does not answer: the file store")
    void linuxNoAnswer() {
        var env = Map.of("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1/bus");

        var selection = SecretStoreSelector.select(paths(MachinePaths.Os.LINUX), env, home, keyrings(true, false));

        assertInstanceOf(FileSecretStore.class, selection.store());
        assertTrue(selection.fallbackReason().orElseThrow().contains("/run/user/1/bus"));
    }

    @Test
    @DisplayName("Linux with only an abstract bus address: the file store without probing")
    void linuxAbstract() {
        var env = Map.of("DBUS_SESSION_BUS_ADDRESS", "unix:abstract=/tmp/dbus-1");

        var selection = SecretStoreSelector.select(paths(MachinePaths.Os.LINUX), env, home, keyrings(true, true));

        assertInstanceOf(FileSecretStore.class, selection.store());
        assertTrue(probes.isEmpty());
        assertTrue(selection.fallbackReason().orElseThrow().contains("unix:path"));
    }

    @Test
    @DisplayName("the running process selects a backend")
    void current() {
        var selection = SecretStoreSelector.select();

        assertTrue(List.of(SecretStore.KEYCHAIN, SecretStore.SECRET_SERVICE, SecretStore.FILE)
                .contains(selection.store().name()));
    }
}
