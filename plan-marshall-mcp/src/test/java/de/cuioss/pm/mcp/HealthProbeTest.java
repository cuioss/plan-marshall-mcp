/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: FSL-1.1-ALv2
 *
 * Licensed under the Functional Source License, Version 1.1, ALv2 Future License
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE.md file at the root of this
 * repository or at https://github.com/cuioss/plan-marshall-mcp/blob/main/LICENSE.md
 */
package de.cuioss.pm.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HealthProbe")
class HealthProbeTest {

    @Nested
    @DisplayName("isProbe")
    class IsProbe {

        @Test
        @DisplayName("recognises the exact flag")
        void shouldRecogniseFlag() {
            assertTrue(HealthProbe.isProbe(new String[]{"-Dfoo=bar", HealthProbe.PROBE_FLAG}));
        }

        @Test
        @DisplayName("ignores other and similar arguments")
        void shouldIgnoreOtherArguments() {
            assertFalse(HealthProbe.isProbe(new String[]{}));
            assertFalse(HealthProbe.isProbe(new String[]{"--health-probe=true", "--health"}));
        }
    }

    @Nested
    @DisplayName("probe")
    class Probe {

        @Test
        @DisplayName("returns 0 for a listening port")
        void shouldSucceedOnListeningPort() throws Exception {
            try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                assertEquals(0, HealthProbe.probe(server.getLocalPort()));
            }
        }

        @Test
        @DisplayName("returns 1 for a closed port")
        void shouldFailOnClosedPort() throws Exception {
            int port;
            try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                port = server.getLocalPort();
            }
            assertEquals(1, HealthProbe.probe(port));
        }
    }
}
