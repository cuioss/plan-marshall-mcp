/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.credentials;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;


import de.planmarshall.api.MachinePaths;
import de.planmarshall.mcp.server.credentials.dbus.DbusConnection;
import lombok.experimental.UtilityClass;

/**
 * Chooses the one active credential backend at runtime start (PM-CRED-2): the macOS Keychain on
 * macOS, the Secret Service on Linux when the session bus answers and the default collection is
 * unlocked, otherwise the file store {@code <PM_MCP_BASE>/credentials/}.
 */
@UtilityClass
public class SecretStoreSelector {

    /** Bound of every Secret Service exchange. */
    static final Duration BUS_TIMEOUT = Duration.ofSeconds(2);

    /**
     * The OS keyrings, separated for tests.
     */
    interface Keyrings {

        /**
         * @param service the service name
         * @return the Keychain backend
         * @throws SecretStoreException if Security.framework cannot be loaded
         */
        SecretStore keychain(String service);

        /**
         * @param socket  the session bus socket
         * @param service the service name
         * @return the Secret Service backend, or empty if it is not usable now
         */
        Optional<SecretStore> secretService(Path socket, String service);
    }

    /**
     * The outcome of the selection.
     *
     * @param store          the active backend
     * @param service        the service name of the instance
     * @param fallbackReason why the file store serves although an OS keyring was looked for, if it does
     */
    public record Selection(SecretStore store, String service, Optional<String> fallbackReason) {
    }

    /** The real keyrings of this machine. */
    static final Keyrings SYSTEM = new Keyrings() {

        @Override
        public SecretStore keychain(String service) {
            return new KeychainSecretStore(service);
        }

        @Override
        public Optional<SecretStore> secretService(Path socket, String service) {
            var store = new SecretServiceStore(socket, Posix.getuid(), service, BUS_TIMEOUT);
            return store.available() ? Optional.of(store) : Optional.empty();
        }
    };

    /**
     * Selects the backend of the running process.
     *
     * @return the selection
     */
    public static Selection select() {
        return select(MachinePaths.current());
    }

    /**
     * Selects the backend of the running process for the given machine root.
     *
     * @param paths the machine paths
     * @return the selection
     */
    public static Selection select(MachinePaths paths) {
        return select(paths, System.getenv(), Path.of(System.getProperty("user.home")), SYSTEM);
    }

    /**
     * @param paths       the machine paths
     * @param environment the process environment
     * @param userHome    the user's home directory
     * @param keyrings    the OS keyrings
     * @return the selection
     */
    static Selection select(MachinePaths paths, Map<String, String> environment, Path userHome, Keyrings keyrings) {
        var service = ServiceName.of(paths.base(), userHome);
        String reason;
        if (paths.os() == MachinePaths.Os.MACOS) {
            try {
                return new Selection(keyrings.keychain(service), service, Optional.empty());
            } catch (SecretStoreException e) {
                reason = "Keychain: " + e.getMessage();
            }
        } else {
            var socket = DbusConnection.sessionBusSocket(environment);
            if (socket.isEmpty()) {
                reason = "Secret Service: no unix:path session bus address";
            } else {
                var store = keyrings.secretService(socket.get(), service);
                if (store.isPresent()) {
                    return new Selection(store.get(), service, Optional.empty());
                }
                reason = "Secret Service: not reachable, or no unlocked default collection on " + socket.get();
            }
        }
        return new Selection(new FileSecretStore(paths.base().resolve("credentials")), service, Optional.of(reason));
    }
}
