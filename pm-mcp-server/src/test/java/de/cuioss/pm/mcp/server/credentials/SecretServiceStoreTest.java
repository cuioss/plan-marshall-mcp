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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;


import de.cuioss.pm.mcp.server.credentials.dbus.FakeBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Secret Service backend against an in-memory secret service on a fake bus (runs on every
 * platform); {@code SecretServiceIT} runs the same operations against a real keyring on Linux.
 */
@DisplayName("SecretServiceStore over a fake session bus")
class SecretServiceStoreTest {

    private static final long UID = 4242;
    private static final String SERVICE = "de.cuioss.pm-mcp";
    private static final CredentialAccount GITHUB = CredentialAccount.global("github");
    private static final CredentialAccount PROJECT_GITHUB = CredentialAccount.project("p1", "github");

    @TempDir
    Path temp;

    private final FakeSecretService secrets = new FakeSecretService();
    private FakeBus bus;
    private SecretServiceStore store;

    @BeforeEach
    void start() throws Exception {
        bus = new FakeBus(temp.resolve("bus"), UID, secrets);
        store = new SecretServiceStore(temp.resolve("bus"), UID, SERVICE, Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() throws Exception {
        bus.close();
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("put creates an item with label and attributes, get reads it, delete removes it")
        void putGetDelete() {
            store.put(GITHUB, "tok€n");

            var item = secrets.items.values().iterator().next();
            assertEquals(Map.of("service", SERVICE, "credential_key", "github"), item.attributes);
            assertEquals(SERVICE + " github", item.label);
            assertEquals(Optional.of("tok€n"), store.get(GITHUB));
            assertTrue(store.delete(GITHUB));
            assertFalse(store.delete(GITHUB));
            assertEquals(Optional.empty(), store.get(GITHUB));
            assertEquals(SecretStore.SECRET_SERVICE, store.name());
        }

        @Test
        @DisplayName("a second put replaces the secret of the same item")
        void replace() {
            store.put(GITHUB, "one");
            store.put(GITHUB, "two");

            assertEquals(1, secrets.items.size());
            assertTrue(secrets.members.contains("SetSecret"));
            assertEquals(Optional.of("two"), store.get(GITHUB));
        }

        @Test
        @DisplayName("a project entry carries the project attribute and resolves before the global one")
        void projectEntry() {
            store.put(PROJECT_GITHUB, "project-token");

            assertEquals(Optional.empty(), store.get(GITHUB), "global lookup ignores project items");
            assertEquals(Optional.of("project-token"), store.resolve("p1", "github"));
            store.put(GITHUB, "global-token");
            assertEquals(Optional.of("project-token"), store.resolve("p1", "github"));
            assertEquals(Optional.of("global-token"), store.resolve("p2", "github"));
            assertEquals(2, secrets.items.size());
        }
    }

    @Nested
    @DisplayName("locked keyring")
    class Locked {

        @Test
        @DisplayName("put into a locked collection reports LOCKED and never calls Unlock")
        void lockedCollection() {
            secrets.collectionLocked = true;

            var e = assertThrows(SecretStoreException.class, () -> store.put(GITHUB, "x"));
            assertEquals(SecretStoreException.Reason.LOCKED, e.reason());
            assertFalse(secrets.members.contains("Unlock"));
        }

        @Test
        @DisplayName("get of a locked item reports LOCKED")
        void lockedItem() {
            store.put(GITHUB, "x");
            secrets.itemsLocked = true;

            var e = assertThrows(SecretStoreException.class, () -> store.get(GITHUB));
            assertEquals(SecretStoreException.Reason.LOCKED, e.reason());
        }

        @Test
        @DisplayName("a CreateItem that needs a prompt reports LOCKED")
        void prompt() {
            secrets.promptOnCreate = true;

            var e = assertThrows(SecretStoreException.class, () -> store.put(GITHUB, "x"));
            assertEquals(SecretStoreException.Reason.LOCKED, e.reason());
        }
    }

    @Nested
    @DisplayName("availability")
    class Availability {

        @Test
        @DisplayName("available with an unlocked default collection")
        void available() {
            assertTrue(store.available());
        }

        @Test
        @DisplayName("not available when locked, without a default collection, or without the service")
        void notAvailable() {
            secrets.collectionLocked = true;
            assertFalse(store.available());
            secrets.collectionLocked = false;
            secrets.noDefaultCollection = true;
            assertFalse(store.available());
            secrets.serviceUnknown = true;
            assertFalse(store.available());
        }

        @Test
        @DisplayName("an unknown service fails operations with FAILED")
        void serviceUnknown() {
            secrets.serviceUnknown = true;

            var e = assertThrows(SecretStoreException.class, () -> store.get(GITHUB));
            assertEquals(SecretStoreException.Reason.FAILED, e.reason());
            assertTrue(e.getMessage().contains("ServiceUnknown"), e.getMessage());
        }

        @Test
        @DisplayName("not available without a bus")
        void noBus() {
            var missing = new SecretServiceStore(temp.resolve("none"), UID, SERVICE, Duration.ofSeconds(1));

            assertFalse(missing.available());
            assertThrows(SecretStoreException.class, () -> missing.delete(GITHUB));
        }
    }
}
