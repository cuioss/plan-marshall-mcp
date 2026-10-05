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

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * The downcall handles of the C library functions {@code pm-api} calls through FFM. Every
 * {@link FunctionDescriptor} used here is registered for native image in
 * {@code META-INF/native-image/de.cuioss/pm-api/reachability-metadata.json}.
 */
final class Libc {

    private static final Linker LINKER = Linker.nativeLinker();

    static final FunctionDescriptor INT_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor INT_PTR_SHORT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_SHORT);
    static final FunctionDescriptor INT_PTR_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor INT_PTR_INT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    static final FunctionDescriptor INT_PTR_INT_INT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor INT_INT_PTR_INT = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
    static final FunctionDescriptor INT_VOID = FunctionDescriptor.of(JAVA_INT);
    static final FunctionDescriptor SPAWN = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
            ADDRESS, ADDRESS);

    private Libc() {
    }

    /**
     * @param name       the C symbol
     * @param descriptor its signature
     * @return the downcall handle
     * @throws NativeCallException if the symbol does not exist
     */
    static MethodHandle handle(String name, FunctionDescriptor descriptor) throws NativeCallException {
        MemorySegment symbol = LINKER.defaultLookup().find(name)
                .orElseThrow(() -> new NativeCallException("C library function not found: " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    /**
     * @param name the C symbol
     * @return {@code true} if the C library exports it
     */
    static boolean exists(String name) {
        return LINKER.defaultLookup().find(name).isPresent();
    }

    /**
     * A downcall; the only checked failure of {@code invokeExact} is the declared {@link Throwable}.
     */
    @FunctionalInterface
    interface Call {
        /**
         * @return the C return value
         * @throws Throwable as declared by {@code MethodHandle.invokeExact}
         */
        @SuppressWarnings("java:S112") // signature-polymorphic invokeExact declares Throwable
        int invoke() throws Throwable;
    }

    /**
     * Runs a downcall and maps a failing C return value.
     *
     * @param function the function name for the diagnostic
     * @param call     the downcall
     * @return the C return value
     * @throws NativeCallException if the downcall itself fails
     */
    @SuppressWarnings({"java:S1181", "squid:S1181"}) // invokeExact declares Throwable
    static int call(String function, Call call) throws NativeCallException {
        try {
            return call.invoke();
        } catch (Error e) {
            throw e;
        }
        /*TODO: Catch specific not Throwable. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
        catch (Throwable e) {
            throw new NativeCallException("Downcall " + function + " failed", e);
        }
    }

    /**
     * Runs a downcall that returns {@code 0} on success and an error number otherwise.
     *
     * @param function the function name for the diagnostic
     * @param call     the downcall
     * @throws NativeCallException if the function reports an error
     */
    static void check(String function, Call call) throws NativeCallException {
        var result = call(function, call);
        if (result != 0) {
            throw new NativeCallException(function + " failed with error " + result);
        }
    }
}
