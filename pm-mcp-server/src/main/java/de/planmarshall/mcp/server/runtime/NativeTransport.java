/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.runtime;

import java.net.SocketAddress;
import java.util.Locale;
import java.util.concurrent.ThreadFactory;


import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.InternetProtocolFamily;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.ClientOptionsBase;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.spi.transport.Transport;

/**
 * The Netty native transport of the platform (kqueue on macOS, epoll on Linux), offered to Vert.x as a
 * {@link Transport} service ({@code META-INF/services/io.vertx.core.spi.transport.Transport}).
 * <p>
 * Vert.x binds Unix domain sockets only with a native transport. In a native image Quarkus 3.39 substitutes
 * {@code VertxBuilder.nativeTransport()} to return the JDK transport, so the built-in lookup never finds kqueue
 * or epoll there; Vert.x consults the {@code Transport} services first, which this class serves. A platform
 * whose transport classes are absent leaves the transport unavailable, and Vert.x falls back to its own lookup.
 *
 * @since 0.1
 */
public class NativeTransport implements Transport {

    private final Transport delegate;
    private final Throwable cause;

    /** Vert.x transport class on macOS. */
    static final String KQUEUE = "io.vertx.core.impl.transports.KQueueTransport";
    /** Vert.x transport class on Linux. */
    static final String EPOLL = "io.vertx.core.impl.transports.EpollTransport";

    /**
     * Selects the transport of the running platform. The class is loaded by name: a direct reference to the other
     * platform's transport does not link in a native image built without that platform's Netty classes. The
     * platform's native-image metadata registers its transport class (see the {@code native-transport-*}
     * profiles of the module POM).
     */
    public NativeTransport() {
        Transport selected = null;
        Throwable failure = null;
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        var name = os.contains("mac") ? KQUEUE : os.contains("linux") ? EPOLL : null;
        if (name != null) {
            try {
                selected = (Transport) Class.forName(name).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                failure = e;
            }
        }
        delegate = selected;
        cause = failure;
    }

    @Override
    public boolean isAvailable() {
        try {
            return delegate != null && delegate.isAvailable();
        } catch (LinkageError _) {
            return false;
        }
    }

    @Override
    public Throwable unavailabilityCause() {
        if (delegate == null) {
            return cause;
        }
        return delegate.unavailabilityCause();
    }

    @Override
    public boolean supportsDomainSockets() {
        return delegate.supportsDomainSockets();
    }

    @Override
    public boolean supportFileRegion() {
        return delegate.supportFileRegion();
    }

    @Override
    public SocketAddress convert(io.vertx.core.net.SocketAddress address) {
        return delegate.convert(address);
    }

    @Override
    public io.vertx.core.net.SocketAddress convert(SocketAddress address) {
        return delegate.convert(address);
    }

    @Override
    public EventLoopGroup eventLoopGroup(int type, int nThreads, ThreadFactory threadFactory, int ioRatio) {
        return delegate.eventLoopGroup(type, nThreads, threadFactory, ioRatio);
    }

    @Override
    public DatagramChannel datagramChannel() {
        return delegate.datagramChannel();
    }

    @Override
    public DatagramChannel datagramChannel(InternetProtocolFamily family) {
        return delegate.datagramChannel(family);
    }

    @Override
    public ChannelFactory<? extends Channel> channelFactory(boolean domainSocket) {
        return delegate.channelFactory(domainSocket);
    }

    @Override
    public ChannelFactory<? extends ServerChannel> serverChannelFactory(boolean domainSocket) {
        return delegate.serverChannelFactory(domainSocket);
    }

    @Override
    public void configure(DatagramChannel channel, DatagramSocketOptions options) {
        delegate.configure(channel, options);
    }

    @Override
    public void configure(ClientOptionsBase options, boolean domainSocket, Bootstrap bootstrap) {
        delegate.configure(options, domainSocket, bootstrap);
    }

    @Override
    public void configure(NetServerOptions options, boolean domainSocket, ServerBootstrap bootstrap) {
        delegate.configure(options, domainSocket, bootstrap);
    }
}
