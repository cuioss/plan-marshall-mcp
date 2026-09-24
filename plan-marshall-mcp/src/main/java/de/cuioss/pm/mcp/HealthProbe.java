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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;


import lombok.experimental.UtilityClass;

/**
 * Pre-boot container health probe invoked by the image's {@code HEALTHCHECK}.
 * <p>
 * Opens a TCP connection to the management port on {@code 127.0.0.1} and closes it again. The
 * probe is protocol-blind, reads no application configuration and never mutates the running
 * instance. The port is the compiled-in Quarkus management default; a deployment overriding
 * {@code quarkus.management.port} needs a rebuilt image.
 *
 * @since 0.1
 */
@UtilityClass
class HealthProbe {

    /** The command-line token requesting a health probe. */
    static final String PROBE_FLAG = "--health-probe";

    /** Quarkus management-port default, the port the image {@code HEALTHCHECK} measures. */
    static final int MANAGEMENT_PORT = 9000;

    private static final int CONNECT_TIMEOUT_MILLIS = 2000;

    /**
     * @param args the raw command line
     * @return {@code true} if {@code args} contains the exact token {@value #PROBE_FLAG}
     */
    static boolean isProbe(String[] args) {
        return Arrays.asList(args).contains(PROBE_FLAG);
    }

    /**
     * Probes the management port.
     *
     * @return {@code 0} if the port accepted a connection, {@code 1} otherwise
     */
    static int probe() {
        return probe(MANAGEMENT_PORT);
    }

    /**
     * Probes the given local port.
     *
     * @param port the port on {@code 127.0.0.1} to connect to
     * @return {@code 0} if the port accepted a connection, {@code 1} otherwise
     */
    static int probe(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS);
            return 0;
        } catch (IOException e) {
            // cui-rewrite:disable CuiLoggerStandardsRecipe
            System.err.println("health-probe: 127.0.0.1:" + port + " not accepting: " + e); // NOSONAR java:S106 pre-boot probe: no logging manager yet; the healthcheck log is the only sink
            return 1;
        }
    }
}
