/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.exec;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The launcher's native calls through the Foreign Function &amp; Memory API against the C library
 * of the default lookup. The Landlock calls go through the libc symbol {@code syscall}, since glibc
 * has no wrappers for them. Every downcall descriptor is registered for native image in
 * {@code META-INF/native-image/de.cuioss/pm-exec/reachability-metadata.json}.
 */
final class NativeKernel implements Kernel {

    /** {@code PR_SET_NO_NEW_PRIVS} (linux/prctl.h). */
    static final int PR_SET_NO_NEW_PRIVS = 38;
    /** {@code O_PATH} on x86_64 and aarch64. */
    static final int O_PATH = 0x200000;
    /** {@code O_CLOEXEC} on x86_64 and aarch64. */
    static final int O_CLOEXEC = 0x80000;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final long ERRNO_OFFSET = CALL_STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private static final Linker.Option ERRNO = Linker.Option.captureCallState("errno");

    /** The native functions, linked on first use. */
    enum Fn {
        /**
         * {@code long syscall(long number, ...)} with four {@code long} arguments: {@code landlock_add_rule}
         * takes four ({@code flags} last, which must be {@code 0}); fewer leave that register undefined and the
         * kernel answers {@code EINVAL}.
         */
        SYSCALL("syscall", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
            Linker.Option.firstVariadicArg(1), ERRNO),
        /** {@code int prctl(int option, ...)} with four {@code long} arguments. */
        PRCTL("prctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
                Linker.Option.firstVariadicArg(1), ERRNO),
        /** {@code int open(const char *path, int flags)}. */
        OPEN("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), ERRNO),
        /** {@code int close(int fd)}. */
        CLOSE("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT)),
        /** {@code int setpgid(pid_t pid, pid_t pgid)}. */
        SETPGID("setpgid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), ERRNO),
        /** {@code int execve(const char *path, char *const argv[], char *const envp[])}. */
        EXECVE("execve", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), ERRNO),
        /** {@code char ***_NSGetEnviron(void)} (macOS). */
        NS_GET_ENVIRON("_NSGetEnviron", FunctionDescriptor.of(ADDRESS));

        private final String symbol;
        private final FunctionDescriptor descriptor;
        private final Linker.Option[] options;

        Fn(String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
            this.symbol = symbol;
            this.descriptor = descriptor;
            this.options = options;
        }
    }

    private final Os os;
    private final Map<Fn, MethodHandle> handles = new EnumMap<>(Fn.class);

    /**
     * @param os the operating system
     */
    NativeKernel(Os os) {
        this.os = os;
    }

    @Override
    public void setNoNewPrivs() throws NativeCallException {
        var handle = handle(Fn.PRCTL);
        try (var arena = Arena.ofConfined()) {
            var state = arena.allocate(CALL_STATE);
            int result = invoke(Fn.PRCTL.symbol,
                    () -> (int) handle.invokeExact(state, PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L));
            check(Fn.PRCTL.symbol, result, state, "PR_SET_NO_NEW_PRIVS");
        }
    }

    @Override
    public int landlockAbi() {
        try {
            long abi = syscall(Landlock.SYS_CREATE_RULESET, 0L, 0L, Landlock.CREATE_RULESET_VERSION,
                    "landlock_create_ruleset");
            return (int) abi;
        } catch (NativeCallException _) {
            // ENOSYS (no Landlock in the kernel) or EOPNOTSUPP (disabled at boot)
            return 0;
        }
    }

    @Override
    public int createRuleset(long handledAccessFs) throws NativeCallException {
        try (var arena = Arena.ofConfined()) {
            var attr = Landlock.rulesetAttr(arena, handledAccessFs);
            return (int) syscall(Landlock.SYS_CREATE_RULESET, attr.address(), Landlock.RULESET_ATTR.byteSize(), 0L,
                    "landlock_create_ruleset");
        }
    }

    @Override
    public OptionalInt openPath(Path path) {
        try (var arena = Arena.ofConfined()) {
            var handle = handle(Fn.OPEN);
            var state = arena.allocate(CALL_STATE);
            var cPath = arena.allocateFrom(path.toString());
            int fd = invoke("open", () -> (int) handle.invokeExact(state, cPath, O_PATH | O_CLOEXEC));
            return fd < 0 ? OptionalInt.empty() : OptionalInt.of(fd);
        } catch (NativeCallException _) {
            return OptionalInt.empty();
        }
    }

    @Override
    public void addPathBeneath(int rulesetFd, int parentFd, long allowedAccess) throws NativeCallException {
        try (var arena = Arena.ofConfined()) {
            var attr = Landlock.pathBeneathAttr(arena, allowedAccess, parentFd);
            syscall(Landlock.SYS_ADD_RULE, rulesetFd, Landlock.RULE_PATH_BENEATH, attr.address(), "landlock_add_rule");
        }
    }

    @Override
    public void restrictSelf(int rulesetFd) throws NativeCallException {
        syscall(Landlock.SYS_RESTRICT_SELF, rulesetFd, 0L, 0L, "landlock_restrict_self");
    }

    @Override
    public void close(int fd) {
        try {
            var handle = handle(Fn.CLOSE);
            invoke("close", () -> (int) handle.invokeExact(fd));
        } catch (NativeCallException _) {
            // nothing to do: the descriptor is CLOEXEC or the ruleset is already applied
        }
    }

    @Override
    public void setProcessGroup() throws NativeCallException {
        var handle = handle(Fn.SETPGID);
        try (var arena = Arena.ofConfined()) {
            var state = arena.allocate(CALL_STATE);
            int result = invoke(Fn.SETPGID.symbol, () -> (int) handle.invokeExact(state, 0, 0));
            check(Fn.SETPGID.symbol, result, state, "setpgid(0, 0)");
        }
    }

    @Override
    public int execve(List<String> argv) {
        try (var arena = Arena.ofConfined()) {
            var handle = handle(Fn.EXECVE);
            var state = arena.allocate(CALL_STATE);
            var cArgv = cStringArray(arena, argv);
            var envp = environment(arena);
            var program = cArgv.getAtIndex(ADDRESS, 0);
            invoke("execve", () -> (int) handle.invokeExact(state, program, cArgv, envp));
            return state.get(JAVA_INT, ERRNO_OFFSET);
        } catch (NativeCallException e) {
            return e.errno();
        }
    }

    /**
     * The process environment as {@code char *const envp[]}: the C library's {@code environ}, so the
     * runtime's environment reaches the program byte for byte; the Java view only as fallback.
     */
    private MemorySegment environment(Arena arena) throws NativeCallException {
        if (os == Os.MACOS) {
            var handle = handle(Fn.NS_GET_ENVIRON);
            MemorySegment pointer = invoke("_NSGetEnviron", () -> (MemorySegment) handle.invokeExact());
            return pointer.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        }
        var symbol = LINKER.defaultLookup().find("environ");
        if (symbol.isPresent()) {
            return symbol.get().reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        }
        var entries = new ArrayList<String>();
        System.getenv().forEach((k, v) -> entries.add(k + "=" + v));
        return cStringArray(arena, entries);
    }

    private static MemorySegment cStringArray(Arena arena, List<String> values) {
        var array = arena.allocate(ADDRESS, values.size() + 1L);
        for (int i = 0; i < values.size(); i++) {
            array.setAtIndex(ADDRESS, i, arena.allocateFrom(values.get(i)));
        }
        array.setAtIndex(ADDRESS, values.size(), MemorySegment.NULL);
        return array;
    }

    private long syscall(long number, long a, long b, long c, String stage) throws NativeCallException {
        return syscall(number, a, b, c, 0L, stage);
    }

    private long syscall(long number, long a, long b, long c, long d, String stage) throws NativeCallException {
        var handle = handle(Fn.SYSCALL);
        try (var arena = Arena.ofConfined()) {
            var state = arena.allocate(CALL_STATE);
            long result = invoke(stage, () -> (long) handle.invokeExact(state, number, a, b, c, d));
            if (result < 0) {
                int errno = state.get(JAVA_INT, ERRNO_OFFSET);
                throw new NativeCallException(stage, errno, stage + " failed with errno " + errno);
            }
            return result;
        }
    }

    private static void check(String stage, int result, MemorySegment state, String what) throws NativeCallException {
        if (result != 0) {
            int errno = state.get(JAVA_INT, ERRNO_OFFSET);
            throw new NativeCallException(stage, errno, what + " failed with errno " + errno);
        }
    }

    private MethodHandle handle(Fn fn) throws NativeCallException {
        var handle = handles.get(fn);
        if (handle == null) {
            var symbol = LINKER.defaultLookup().find(fn.symbol)
                    .orElseThrow(() -> new NativeCallException(fn.symbol, 0, "symbol not found: " + fn.symbol));
            handle = LINKER.downcallHandle(symbol, fn.descriptor, fn.options);
            handles.put(fn, handle);
        }
        return handle;
    }

    /** A downcall; {@link MethodHandle#invokeExact} declares {@link Throwable}. */
    @FunctionalInterface
    private interface Downcall<T> {
        T call() throws Throwable;
    }

    @SuppressWarnings("java:S1181") // MethodHandle.invokeExact declares Throwable; Errors are rethrown
    private static <T> T invoke(String stage, Downcall<T> downcall) throws NativeCallException {
        try {
            return downcall.call();
            // cui-rewrite:disable InvalidExceptionUsageRecipe
        } catch (Throwable t) {
            if (t instanceof Error e) {
                throw e;
            }
            throw new NativeCallException(stage, 0, stage + " could not be called: " + t);
        }
    }
}
