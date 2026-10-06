/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.posix;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts a process through {@code posix_spawn(3)} by FFM, JDK only, following a {@link SpawnPlan};
 * the platform's structure sizes and constants come from {@link PosixPlatform}. Also reaps a started
 * child with {@code waitpid(2)} and reads the effective user id.
 */
public final class PosixSpawn {

    private static final long POINTER_ALIGNMENT = 16;

    private static final String SIGEMPTYSET = "sigemptyset";
    private static final String ADD_OPEN = "posix_spawn_file_actions_addopen";
    private static final String ADD_CLOSE_FROM = "posix_spawn_file_actions_addclosefrom_np";

    private final PosixPlatform platform;

    /**
     * @param platform the platform constants
     */
    public PosixSpawn(PosixPlatform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /**
     * Spawns the plan's program.
     *
     * @param plan the spawn plan
     * @return the process id of the child
     * @throws NativeCallException if a C call fails, {@code posix_spawn} included
     */
    public int spawn(SpawnPlan plan) throws NativeCallException {
        var h = Handles.get(platform);
        try (var arena = Arena.ofConfined()) {
            var attr = arena.allocate(platform.spawnAttrSize(), POINTER_ALIGNMENT);
            var actions = arena.allocate(platform.fileActionsSize(), POINTER_ALIGNMENT);
            Libc.check("posix_spawnattr_init", () -> (int) h.attrInit.invokeExact(attr));
            try {
                Libc.check("posix_spawn_file_actions_init", () -> (int) h.actionsInit.invokeExact(actions));
                try {
                    configureAttributes(h, arena, attr, plan);
                    addFileActions(h, arena, actions, plan.fileActions());
                    var pid = arena.allocate(JAVA_INT);
                    var path = arena.allocateFrom(plan.executable().toString());
                    var argv = stringArray(arena, plan.argv());
                    var envp = stringArray(arena, plan.environment());
                    Libc.check("posix_spawn " + plan.executable(),
                            () -> (int) h.spawn.invokeExact(pid, path, actions, attr, argv, envp));
                    return pid.get(JAVA_INT, 0);
                } finally {
                    Libc.call("posix_spawn_file_actions_destroy", () -> (int) h.actionsDestroy.invokeExact(actions));
                }
            } finally {
                Libc.call("posix_spawnattr_destroy", () -> (int) h.attrDestroy.invokeExact(attr));
            }
        }
    }

    private void configureAttributes(Handles h, Arena arena, MemorySegment attr, SpawnPlan plan)
            throws NativeCallException {
        var flags = plan.flags();
        Libc.check("posix_spawnattr_setflags", () -> (int) h.setFlags.invokeExact(attr, flags));
        var emptyMask = arena.allocate(platform.sigsetSize(), POINTER_ALIGNMENT);
        Libc.check(SIGEMPTYSET, () -> (int) h.sigEmptySet.invokeExact(emptyMask));
        Libc.check("posix_spawnattr_setsigmask", () -> (int) h.setSigMask.invokeExact(attr, emptyMask));
        var defaults = arena.allocate(platform.sigsetSize(), POINTER_ALIGNMENT);
        Libc.check(SIGEMPTYSET, () -> (int) h.sigEmptySet.invokeExact(defaults));
        for (int signal : plan.defaultSignals()) {
            Libc.check("sigaddset", () -> (int) h.sigAddSet.invokeExact(defaults, signal));
        }
        Libc.check("posix_spawnattr_setsigdefault", () -> (int) h.setSigDefault.invokeExact(attr, defaults));
    }

    private void addFileActions(Handles h, Arena arena, MemorySegment actions, List<SpawnPlan.FileAction> plan)
            throws NativeCallException {
        for (var action : plan) {
            switch (action) {
                case SpawnPlan.Open open -> {
                    var path = arena.allocateFrom(open.path());
                    if (platform.modeLayout() == JAVA_SHORT) {
                        var mode = (short) open.mode();
                        Libc.check(ADD_OPEN, () -> (int) h.addOpen
                                .invokeExact(actions, open.fd(), path, open.flags(), mode));
                    } else {
                        Libc.check(ADD_OPEN, () -> (int) h.addOpen
                                .invokeExact(actions, open.fd(), path, open.flags(), open.mode()));
                    }
                }
                case SpawnPlan.Dup2 dup2 -> Libc.check("posix_spawn_file_actions_adddup2",
                        () -> (int) h.addDup2.invokeExact(actions, dup2.fd(), dup2.newFd()));
                case SpawnPlan.CloseFrom closeFrom -> {
                    if (h.addCloseFrom == null) {
                        throw new NativeCallException(
                                ADD_CLOSE_FROM + " is unavailable (glibc 2.34 or later required)");
                    }
                    Libc.check(ADD_CLOSE_FROM,
                            () -> (int) h.addCloseFrom.invokeExact(actions, closeFrom.lowFd()));
                }
            }
        }
    }

    private static MemorySegment stringArray(Arena arena, List<String> values) {
        var array = arena.allocate(ADDRESS, values.size() + 1L);
        for (var i = 0; i < values.size(); i++) {
            array.setAtIndex(ADDRESS, i, arena.allocateFrom(values.get(i)));
        }
        array.setAtIndex(ADDRESS, values.size(), MemorySegment.NULL);
        return array;
    }

    /**
     * Waits for a child to terminate and reaps it ({@code waitpid(pid, &status, 0)}).
     *
     * @param pid the child's process id
     * @return the raw wait status
     * @throws NativeCallException if {@code waitpid} fails
     */
    public int waitFor(int pid) throws NativeCallException {
        var h = Handles.get(platform);
        try (var arena = Arena.ofConfined()) {
            var status = arena.allocate(JAVA_INT);
            var result = Libc.call("waitpid", () -> (int) h.waitPid.invokeExact(pid, status, 0));
            if (result != pid) {
                throw new NativeCallException("waitpid(" + pid + ") returned " + result);
            }
            return status.get(JAVA_INT, 0);
        }
    }

    /**
     * @param rawStatus a wait status
     * @return the exit code if the child exited normally, else {@code 128 + signal}
     */
    public static int exitCode(int rawStatus) {
        var signal = rawStatus & 0x7f;
        return signal == 0 ? rawStatus >> 8 & 0xff : 128 + signal;
    }

    /**
     * @return the effective user id of this process ({@code geteuid(2)})
     * @throws NativeCallException if the call fails
     */
    public int effectiveUserId() throws NativeCallException {
        var h = Handles.get(platform);
        return Libc.call("geteuid", () -> (int) h.geteuid.invokeExact());
    }

    /** The downcall handles, created once per platform on first use. */
    private static final class Handles {
        private static final AtomicReference<Handles> INSTANCE = new AtomicReference<>();

        final MethodHandle attrInit;
        final MethodHandle attrDestroy;
        final MethodHandle setFlags;
        final MethodHandle setSigMask;
        final MethodHandle setSigDefault;
        final MethodHandle sigEmptySet;
        final MethodHandle sigAddSet;
        final MethodHandle actionsInit;
        final MethodHandle actionsDestroy;
        final MethodHandle addOpen;
        final MethodHandle addDup2;
        final MethodHandle addCloseFrom;
        final MethodHandle spawn;
        final MethodHandle waitPid;
        final MethodHandle geteuid;

        private Handles(PosixPlatform platform) throws NativeCallException {
            attrInit = Libc.handle("posix_spawnattr_init", Libc.INT_PTR);
            attrDestroy = Libc.handle("posix_spawnattr_destroy", Libc.INT_PTR);
            setFlags = Libc.handle("posix_spawnattr_setflags", Libc.INT_PTR_SHORT);
            setSigMask = Libc.handle("posix_spawnattr_setsigmask", Libc.INT_PTR_PTR);
            setSigDefault = Libc.handle("posix_spawnattr_setsigdefault", Libc.INT_PTR_PTR);
            sigEmptySet = Libc.handle(SIGEMPTYSET, Libc.INT_PTR);
            sigAddSet = Libc.handle("sigaddset", Libc.INT_PTR_INT);
            actionsInit = Libc.handle("posix_spawn_file_actions_init", Libc.INT_PTR);
            actionsDestroy = Libc.handle("posix_spawn_file_actions_destroy", Libc.INT_PTR);
            addOpen = Libc.handle(ADD_OPEN,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, platform.modeLayout()));
            addDup2 = Libc.handle("posix_spawn_file_actions_adddup2", Libc.INT_PTR_INT_INT);
            addCloseFrom = platform.usesCloseFromAction() && Libc.exists(ADD_CLOSE_FROM)
                    ? Libc.handle(ADD_CLOSE_FROM, Libc.INT_PTR_INT)
                    : null;
            spawn = Libc.handle("posix_spawn", Libc.SPAWN);
            waitPid = Libc.handle("waitpid", Libc.INT_INT_PTR_INT);
            geteuid = Libc.handle("geteuid", Libc.INT_VOID);
        }

        static Handles get(PosixPlatform platform) throws NativeCallException {
            var local = INSTANCE.get();
            if (local == null) {
                synchronized (Handles.class) {
                    local = INSTANCE.get();
                    if (local == null) {
                        local = new Handles(platform);
                        INSTANCE.set(local);
                    }
                }
            }
            return local;
        }
    }
}
