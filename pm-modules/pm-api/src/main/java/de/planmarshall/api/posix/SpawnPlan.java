/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.posix;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Everything one {@code posix_spawn} call needs, built in plain Java so it can be checked without
 * native code.
 *
 * @param executable     the absolute path of the program, never looked up through {@code PATH}
 * @param argv           the argument vector, {@code argv[0]} first
 * @param environment    the environment as {@code NAME=value} entries
 * @param flags          the {@code posix_spawnattr_setflags} value
 * @param defaultSignals the signals reset to their default disposition
 * @param fileActions    the file actions in order
 */
public record SpawnPlan(Path executable, List<String> argv, List<String> environment, short flags,
List<Integer> defaultSignals, List<FileAction> fileActions) {

    /** Environment variables never passed to a started runtime. */
    static final List<String> WITHHELD_VARIABLES = List.of("PM_MCP_JOB_TOKEN");

    /**
     * Validates and copies the components.
     *
     * @param executable     the program
     * @param argv           the arguments
     * @param environment    the environment
     * @param flags          the flags
     * @param defaultSignals the signals reset to default
     * @param fileActions    the file actions
     */
    public SpawnPlan {
        Objects.requireNonNull(executable, "executable");
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("Executable must be an absolute path: " + executable);
        }
        argv = List.copyOf(argv);
        environment = List.copyOf(environment);
        defaultSignals = List.copyOf(defaultSignals);
        fileActions = List.copyOf(fileActions);
    }

    /**
     * One file action of {@code posix_spawn_file_actions_t}.
     */
    public sealed interface FileAction {
    }

    /**
     * {@code posix_spawn_file_actions_addopen}.
     *
     * @param fd    the descriptor to open
     * @param path  the file
     * @param flags the {@code open} flags
     * @param mode  the creation mode
     */
    public record Open(int fd, String path, int flags, int mode) implements FileAction {
    }

    /**
     * {@code posix_spawn_file_actions_adddup2}.
     *
     * @param fd    the source descriptor
     * @param newFd the target descriptor
     */
    public record Dup2(int fd, int newFd) implements FileAction {
    }

    /**
     * {@code posix_spawn_file_actions_addclosefrom_np}.
     *
     * @param lowFd the lowest descriptor closed
     */
    public record CloseFrom(int lowFd) implements FileAction {
    }

    /**
     * The detached start of the runtime: a new session ({@code POSIX_SPAWN_SETSID}), {@code stdin}
     * from {@code /dev/null}, {@code stdout} appended to the daemon log ({@code 0600} when created),
     * {@code stderr} a duplicate of {@code stdout}, no other inherited descriptor, an empty signal
     * mask, and the default disposition of the termination signals.
     *
     * @param platform    the platform constants
     * @param executable  the absolute path of {@code pm-mcpd}
     * @param log         the daemon log
     * @param environment the environment of the starting process
     * @return the plan
     */
    public static SpawnPlan detachedDaemon(PosixPlatform platform, Path executable, Path log,
            Map<String, String> environment) {
        var actions = new ArrayList<FileAction>();
        actions.add(new Open(0, "/dev/null", PosixPlatform.O_RDONLY, 0));
        actions.add(new Open(1, log.toString(), platform.appendFlags(), 0600));
        actions.add(new Dup2(1, 2));
        if (platform.usesCloseFromAction()) {
            actions.add(new CloseFrom(3));
        }
        var env = new ArrayList<String>();
        new TreeMap<>(environment).forEach((name, value) -> {
            if (!WITHHELD_VARIABLES.contains(name)) {
                env.add(name + "=" + value);
            }
        });
        var signals = new ArrayList<Integer>();
        for (var signal : PosixPlatform.DEFAULT_SIGNALS) {
            signals.add(signal);
        }
        return new SpawnPlan(executable, List.of(executable.toString()), env, platform.detachedSpawnFlags(),
                signals, actions);
    }
}
