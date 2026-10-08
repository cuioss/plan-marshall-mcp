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
 * of the default lookup. This class holds the downcall plumbing (linking, {@code errno} capture)
 * and the calls every platform has: {@code close}, {@code setpgid}, {@code execve} and the process
 * environment. The calls only the Linux kernel has are made by {@link LinuxCalls}, to which the
 * Linux methods of {@link Kernel} delegate. Every downcall descriptor is registered for native
 * image in {@code META-INF/native-image/de.planmarshall/pm-exec/reachability-metadata.json}.
 */
final class NativeKernel implements Kernel {

    /** The layout of the captured call state; a call linked with {@code errno} capture takes a segment of it. */
    static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();

    private static final Linker LINKER = Linker.nativeLinker();
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

        /** The name of the function in the C library. */
        final String symbol;
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
    private final LinuxCalls linux = new LinuxCalls(this);

    /**
     * @param os the operating system
     */
    NativeKernel(Os os) {
        this.os = os;
    }

    @Override
    public void setNoNewPrivs() throws NativeCallException {
        linux.setNoNewPrivs();
    }

    @Override
    public int landlockAbi() {
        return linux.landlockAbi();
    }

    @Override
    public int createRuleset(long handledAccessFs) throws NativeCallException {
        return linux.createRuleset(handledAccessFs);
    }

    @Override
    public OptionalInt openPath(Path path) {
        return linux.openPath(path);
    }

    @Override
    public void addPathBeneath(int rulesetFd, int parentFd, long allowedAccess) throws NativeCallException {
        linux.addPathBeneath(rulesetFd, parentFd, allowedAccess);
    }

    @Override
    public void restrictSelf(int rulesetFd) throws NativeCallException {
        linux.restrictSelf(rulesetFd);
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
            return errno(state);
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

    /**
     * @param state the call state a downcall captured
     * @return the {@code errno} of that call
     */
    static int errno(MemorySegment state) {
        return state.get(JAVA_INT, ERRNO_OFFSET);
    }

    /**
     * Turns a non-zero result into a failed native call.
     *
     * @param stage  the function that was called
     * @param result its return value
     * @param state  the call state it captured
     * @param what   the call as the message names it
     * @throws NativeCallException if {@code result} is not {@code 0}
     */
    static void check(String stage, int result, MemorySegment state, String what) throws NativeCallException {
        if (result != 0) {
            int errno = errno(state);
            throw new NativeCallException(stage, errno, what + " failed with errno " + errno);
        }
    }

    /**
     * @param fn the native function
     * @return its downcall handle, linked on first use
     * @throws NativeCallException if the C library has no such symbol
     */
    MethodHandle handle(Fn fn) throws NativeCallException {
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
    interface Downcall<T> {
        T call() throws Throwable;
    }

    /**
     * Runs a downcall.
     *
     * @param stage    the function, for the failure
     * @param downcall the call
     * @param <T>      its return type
     * @return its return value
     * @throws NativeCallException if the call could not be made
     */
    @SuppressWarnings("java:S1181") // MethodHandle.invokeExact declares Throwable; Errors are rethrown
    static <T> T invoke(String stage, Downcall<T> downcall) throws NativeCallException {
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
