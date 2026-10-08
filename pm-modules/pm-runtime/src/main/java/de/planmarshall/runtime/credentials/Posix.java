/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.credentials;

import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;


import lombok.experimental.UtilityClass;

/**
 * The process uid through FFM {@code getuid()}, for the SASL {@code EXTERNAL} identity of the D-Bus
 * connection (no JNI library).
 */
@UtilityClass
class Posix {

    /**
     * @return the real uid of this process
     * @throws SecretStoreException if the C library offers no {@code getuid}
     */
    @SuppressWarnings("java:S1181") // MethodHandle.invokeExact declares Throwable; Errors are rethrown
    static long getuid() {
        var linker = Linker.nativeLinker();
        var symbol = linker.defaultLookup().find("getuid")
                .orElseThrow(() -> new SecretStoreException(SecretStoreException.Reason.FAILED, "getuid not found"));
        var handle = linker.downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT));
        try {
            return Integer.toUnsignedLong((int) handle.invokeExact());
            // cui-rewrite:disable InvalidExceptionUsageRecipe
        } catch (Throwable t) {
            if (t instanceof Error e) {
                throw e;
            }
            throw new SecretStoreException(SecretStoreException.Reason.FAILED, "getuid failed", t);
        }
    }
}
