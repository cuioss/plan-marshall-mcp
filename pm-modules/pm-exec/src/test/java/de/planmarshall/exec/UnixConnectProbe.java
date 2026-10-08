/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.exec;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * The job program of the session-bus check in {@link PmExecIT}: started through {@code pm-exec}, it reports on
 * {@code stdout} whether {@code DBUS_SESSION_BUS_ADDRESS} reached it and whether {@code connect(2)} to each given
 * pathname Unix socket succeeded, one line {@code <path> connected} or {@code <path> refused <message>} per
 * socket. A test fixture only, never part of the product.
 */
final class UnixConnectProbe {

    private UnixConnectProbe() {
    }

    /**
     * @param args the socket paths to connect to
     */
    public static void main(String[] args) {
        var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        out.println("dbus_env " + (System.getenv("DBUS_SESSION_BUS_ADDRESS") == null ? "absent" : "present"));
        for (var path : args) {
            try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(path));
                out.println(path + " connected");
            } catch (IOException e) {
                out.println(path + " refused " + e.getMessage());
            }
        }
    }
}
