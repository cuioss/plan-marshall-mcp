/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;


import io.netty.channel.socket.InternetProtocolFamily;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisplayName("Native transport and library path")
class NativeTransportTest {

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @DisplayName("offers the platform's native transport with domain sockets")
    void shouldOfferNativeTransport() {
        var transport = new NativeTransport();

        assertTrue(transport.isAvailable(), String.valueOf(transport.unavailabilityCause()));
        assertNull(transport.unavailabilityCause());
        assertTrue(transport.supportsDomainSockets());
        assertTrue(transport.supportFileRegion());
        assertNotNull(transport.channelFactory(true));
        assertNotNull(transport.serverChannelFactory(true));
        var address = SocketAddress.domainSocketAddress("/tmp/x.sock");
        assertEquals(address.path(), transport.convert(transport.convert(address)).path());
        var group = transport.eventLoopGroup(1, 1, Thread::new, 50);
        try {
            assertNotNull(group);
        } finally {
            group.shutdownGracefully();
        }
        var channel = transport.datagramChannel();
        assertNotNull(channel);
        assertNotNull(transport.datagramChannel(InternetProtocolFamily.IPv4));
    }

    @Test
    @DisplayName("prepends the executable directory to the library path")
    void shouldPrependLibraryPath() {
        var dir = Path.of("/opt/pm/bin");

        assertEquals("/opt/pm/bin", NativeLibraryPath.prepend(dir, null));
        assertEquals("/opt/pm/bin", NativeLibraryPath.prepend(dir, ""));
        assertEquals("/opt/pm/bin" + File.pathSeparator + "/usr/lib", NativeLibraryPath.prepend(dir, "/usr/lib"));
    }

    @Test
    @DisplayName("leaves the library path of a JVM alone")
    void shouldIgnoreJvm() {
        var before = System.getProperty(NativeLibraryPath.LIBRARY_PATH);

        NativeLibraryPath.includeExecutableDirectory();

        assertEquals(before, System.getProperty(NativeLibraryPath.LIBRARY_PATH));
    }
}
