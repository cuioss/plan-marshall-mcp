/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The paths of the machine root {@code <PM_MCP_BASE>} that the runtime and every local client derive
 * the same way (PM-SEC-4, PM-ARCH-1): the runtime socket, the runtime token, the runtime record and
 * the two locks.
 * <p>
 * {@code PM_MCP_BASE} is taken from the environment variable of the same name and defaults to
 * {@code ~/.plan-marshall-mcp}. The socket has no other location: a path over the platform's
 * {@code sun_path} limit refuses the runtime start (see {@link #socketPathFits()}).
 *
 * @param base the machine root {@code <PM_MCP_BASE>}, absolute and normalized
 * @param os   the operating system the limits apply to
 * @since 0.1
 */
public record MachinePaths(Path base, Os os) {

    /** Environment variable naming the machine root. */
    public static final String ENV_BASE = "PM_MCP_BASE";

    /** Default machine root below the user's home directory. */
    public static final String DEFAULT_BASE_DIR = ".plan-marshall-mcp";

    /**
     * Operating systems with their {@code sun_path} limit in bytes, the terminating NUL included.
     */
    public enum Os {
        /** macOS: 104 bytes. */
        MACOS(104),
        /** Linux: 108 bytes. */
        LINUX(108);

        private final int sunPathLimit;

        Os(int sunPathLimit) {
            this.sunPathLimit = sunPathLimit;
        }

        /**
         * @return the {@code sun_path} limit in bytes including the terminating NUL
         */
        public int sunPathLimit() {
            return sunPathLimit;
        }

        /**
         * Resolves the operating system from an {@code os.name} value.
         *
         * @param osName the value of the system property {@code os.name}
         * @return the matching operating system
         * @throws IllegalStateException for a platform PM-MCP does not support (PM-TECH-4)
         */
        public static Os of(String osName) {
            var name = Objects.requireNonNull(osName, "osName").toLowerCase(Locale.ROOT);
            if (name.contains("mac")) {
                return MACOS;
            }
            if (name.contains("linux")) {
                return LINUX;
            }
            throw new IllegalStateException("Unsupported operating system: " + osName);
        }

        /**
         * @return the operating system of the running process
         */
        public static Os current() {
            return of(System.getProperty("os.name"));
        }
    }

    /**
     * Normalizes the base to an absolute path.
     *
     * @param base the machine root
     * @param os   the operating system
     */
    public MachinePaths {
        base = Objects.requireNonNull(base, "base").toAbsolutePath().normalize();
        Objects.requireNonNull(os, "os");
    }

    /**
     * Resolves the machine root from an environment and a home directory.
     *
     * @param environment the process environment
     * @param userHome    the user's home directory
     * @param os          the operating system
     * @return the machine paths
     */
    public static MachinePaths resolve(Map<String, String> environment, Path userHome, Os os) {
        var configured = environment.get(ENV_BASE);
        var base = configured == null || configured.isBlank() ? userHome.resolve(DEFAULT_BASE_DIR) : Path.of(configured);
        return new MachinePaths(base, os);
    }

    /**
     * @return the machine paths of the running process
     */
    public static MachinePaths current() {
        return resolve(System.getenv(), Path.of(System.getProperty("user.home")), Os.current());
    }

    /** @return {@code <PM_MCP_BASE>/run}, mode {@code 0700} */
    public Path runDir() {
        return base.resolve("run");
    }

    /** @return {@code <PM_MCP_BASE>/run/runtime.sock}, mode {@code 0600} */
    public Path socket() {
        return runDir().resolve("runtime.sock");
    }

    /** @return {@code <PM_MCP_BASE>/run/runtime.token}, mode {@code 0600} */
    public Path runtimeToken() {
        return runDir().resolve("runtime.token");
    }

    /** @return {@code <PM_MCP_BASE>/state}, mode {@code 0700} */
    public Path stateDir() {
        return base.resolve("state");
    }

    /** @return {@code <PM_MCP_BASE>/state/runtime.json}, the runtime record */
    public Path runtimeRecord() {
        return stateDir().resolve("runtime.json");
    }

    /** @return {@code <PM_MCP_BASE>/locks} */
    public Path locksDir() {
        return base.resolve("locks");
    }

    /** @return {@code <PM_MCP_BASE>/locks/runtime.lock}, the runtime singleton lock */
    public Path runtimeLock() {
        return locksDir().resolve("runtime.lock");
    }

    /** @return {@code <PM_MCP_BASE>/locks/runtime-start.lock}, the on-demand start lock */
    public Path runtimeStartLock() {
        return locksDir().resolve("runtime-start.lock");
    }

    /** @return {@code <PM_MCP_BASE>/logs} */
    public Path logsDir() {
        return base.resolve("logs");
    }

    /** @return {@code <PM_MCP_BASE>/logs/daemon.log}, {@code stdout} and {@code stderr} of a spawned runtime */
    public Path daemonLog() {
        return logsDir().resolve("daemon.log");
    }

    /**
     * Checks the socket path against the platform's {@code sun_path} limit.
     *
     * @return {@code true} if the UTF-8 bytes of the socket path plus the terminating NUL fit
     */
    public boolean socketPathFits() {
        return socketPathBytes() + 1 <= os.sunPathLimit();
    }

    /**
     * @return the length of the socket path in UTF-8 bytes, without the terminating NUL
     */
    public int socketPathBytes() {
        return socket().toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
