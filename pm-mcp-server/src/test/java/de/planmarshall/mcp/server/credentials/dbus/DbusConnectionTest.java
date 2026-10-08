/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.credentials.dbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("DbusConnection: SASL, Hello and calls over a Unix socket")
class DbusConnectionTest {

    private static final long UID = 501;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path temp;

    private FakeBus bus(FakeBus.Handler handler) throws Exception {
        return new FakeBus(temp.resolve("bus"), UID, handler);
    }

    @Nested
    @DisplayName("open")
    class Open {

        @Test
        @DisplayName("authenticates with the hex-encoded uid and receives the unique name")
        void hello() throws Exception {
            try (var bus = bus(_ -> null); var connection = DbusConnection.open(temp.resolve("bus"), UID, TIMEOUT)) {
                assertEquals(":1.7", connection.uniqueName());
                assertEquals(List.of("AUTH EXTERNAL 353031", "BEGIN"), bus.authLines());
                assertEquals("Hello", bus.calls().getFirst().member());
            }
        }

        @Test
        @DisplayName("fails when the bus refuses the identity")
        void rejected() throws Exception {
            try (var _ = bus(_ -> null)) {
                var socket = temp.resolve("bus");
                var e = assertThrows(DbusException.class, () -> DbusConnection.open(socket, 0, TIMEOUT));
                assertEquals("org.freedesktop.DBus.Error.AuthFailed", e.errorName());
            }
        }

        @Test
        @DisplayName("fails without a bus")
        void noBus() {
            var socket = temp.resolve("missing");

            var e = assertThrows(DbusException.class, () -> DbusConnection.open(socket, UID, TIMEOUT));
            assertEquals(DbusException.LOCAL, e.errorName());
        }
    }

    @Nested
    @DisplayName("call")
    class Call {

        @Test
        @DisplayName("returns the reply body of its own serial")
        void reply() throws Exception {
            FakeBus.Handler echo = call -> new FakeBus.Reply("su", List.of(call.member(), 42L));
            try (var _ = bus(echo); var connection = DbusConnection.open(temp.resolve("bus"), UID, TIMEOUT)) {
                var body = connection.call("org.example", "/o", "org.example.I", "Ping", "s", List.of("x"));

                assertEquals(List.of("Ping", 42L), body);
            }
        }

        @Test
        @DisplayName("throws an error reply with its name")
        void error() throws Exception {
            FakeBus.Handler refuse = _ -> new FakeBus.ErrorReply("org.freedesktop.DBus.Error.ServiceUnknown", "gone");
            try (var _ = bus(refuse); var connection = DbusConnection.open(temp.resolve("bus"), UID, TIMEOUT)) {
                var e = assertThrows(DbusException.class,
                        () -> connection.call("org.example", "/o", "org.example.I", "Ping", "", List.of()));

                assertEquals("org.freedesktop.DBus.Error.ServiceUnknown", e.errorName());
                assertTrue(e.getMessage().contains("gone"));
            }
        }

        @Test
        @DisplayName("gives up after the timeout instead of hanging")
        void timeout() throws Exception {
            try (var _ = bus(_ -> null);
                 var connection = DbusConnection.open(temp.resolve("bus"), UID, Duration.ofMillis(300))) {
                long start = System.nanoTime();
                var e = assertThrows(DbusException.class,
                        () -> connection.call("org.example", "/o", "org.example.I", "Ping", "", List.of()));

                assertTrue(e.getMessage().contains("no answer"), e.getMessage());
                assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
            }
        }
    }

    @Nested
    @DisplayName("sessionBusSocket")
    class SessionBusSocket {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', value = {
                "unix:path=/run/user/1000/bus | /run/user/1000/bus",
                "unix:abstract=/tmp/dbus-x,guid=1;unix:path=/tmp/a%20b,guid=2 | /tmp/a b",
                "unix:abstract=/tmp/dbus-x,guid=1 | ''",
                "tcp:host=localhost,port=1 | ''"})
        @DisplayName("takes the first unix:path address, skips abstract sockets")
        void address(String address, String expected) {
            var socket = DbusConnection.sessionBusSocket(Map.of("DBUS_SESSION_BUS_ADDRESS", address));

            assertEquals(expected.isEmpty() ? Optional.empty() : Optional.of(Path.of(expected)), socket);
        }

        @Test
        @DisplayName("falls back to $XDG_RUNTIME_DIR/bus without an address")
        void runtimeDir() {
            assertEquals(Optional.of(Path.of("/run/user/7/bus")),
                    DbusConnection.sessionBusSocket(Map.of("XDG_RUNTIME_DIR", "/run/user/7")));
            assertEquals(Optional.empty(), DbusConnection.sessionBusSocket(new HashMap<>()));
        }
    }
}
