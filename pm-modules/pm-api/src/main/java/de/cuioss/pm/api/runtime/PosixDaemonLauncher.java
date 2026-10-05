/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import de.cuioss.pm.api.posix.NativeCallException;
import de.cuioss.pm.api.posix.PosixPlatform;
import de.cuioss.pm.api.posix.PosixSpawn;
import de.cuioss.pm.api.posix.SpawnPlan;

/**
 * Starts {@code pm-mcpd} with {@code posix_spawn} and {@code POSIX_SPAWN_SETSID} (a new session, so
 * a host terminating the client or its process group never reaches the runtime) and reaps the child
 * on a daemon thread, so a long-lived client holds no zombie and learns an early exit.
 */
public final class PosixDaemonLauncher implements DaemonLauncher {

    private static final int RUNNING = Integer.MIN_VALUE;

    private final PosixPlatform platform;
    private final PosixSpawn spawn;
    private final Map<String, String> environment;

    /**
     * @param platform    the platform constants
     * @param environment the environment passed to the runtime
     */
    public PosixDaemonLauncher(PosixPlatform platform, Map<String, String> environment) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.spawn = new PosixSpawn(platform);
        this.environment = Map.copyOf(environment);
    }

    @Override
    public Launched launch(Path executable, Path log) throws IOException {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new RuntimeUnavailableException("The runtime executable does not exist or is not executable: "
                    + executable);
        }
        var pid = spawn.spawn(SpawnPlan.detachedDaemon(platform, executable, log, environment));
        var exit = new AtomicInteger(RUNNING);
        Thread.ofPlatform().daemon().name("pm-mcpd-reaper-" + pid).start(() -> {
            try {
                exit.set(PosixSpawn.exitCode(spawn.waitFor(pid)));
            } catch (NativeCallException e) {
                // the child cannot be reaped; its exit stays unknown
            }
        });
        return new Launched() {
            @Override
            public int pid() {
                return pid;
            }

            @Override
            public OptionalInt exitCode() {
                var code = exit.get();
                return code == RUNNING ? OptionalInt.empty() : OptionalInt.of(code);
            }
        };
    }
}
