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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.pm.api.MachinePaths;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The identity of this runtime instance: its machine paths, process, version, start time and runtime token.
 * <p>
 * The base is the one {@link PmMcpd} locked (system property {@value PmMcpd#BASE_PROPERTY}); without it, the
 * base of {@code PM_MCP_BASE}. The token is read once from {@code run/runtime.token}, which the startup
 * sequence wrote before Quarkus started; it never leaves this bean except as bytes for the constant-time
 * comparison.
 *
 * @since 0.1
 */
@ApplicationScoped
public class RuntimeContext {

    /** Listener kind of the Unix domain socket in the runtime record and the status. */
    public static final String LISTENER_UNIX = "unix";

    private final MachinePaths paths;
    private final String version;
    private final Instant startedAt = Instant.now();
    private final AtomicReference<byte[]> token = new AtomicReference<>();

    /**
     * @param base    the locked machine root, if passed by the entry point
     * @param version the application version
     */
    public RuntimeContext(@ConfigProperty(name = PmMcpd.BASE_PROPERTY) Optional<String> base,
            @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev") String version) {
        this.paths = base.map(value -> new MachinePaths(Path.of(value), MachinePaths.Os.current()))
                .orElseGet(MachinePaths::current);
        this.version = version;
    }

    /** @return the machine paths of this runtime */
    public MachinePaths paths() {
        return paths;
    }

    /** @return the version of this runtime */
    public String version() {
        return version;
    }

    /** @return when this runtime's beans were created */
    public Instant startedAt() {
        return startedAt;
    }

    /** @return the process id */
    public long pid() {
        return ProcessHandle.current().pid();
    }

    /**
     * @return the runtime token as UTF-8 bytes, read once from the token file
     * @throws UncheckedIOException if the token file cannot be read
     */
    public byte[] tokenBytes() {
        var current = token.get();
        if (current == null) {
            try {
                current = new RuntimeTokenFile(paths.runtimeToken()).read().getBytes(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            token.set(current);
        }
        return current;
    }
}
