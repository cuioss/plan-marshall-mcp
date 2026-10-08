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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FFM bindings of the macOS Security and CoreFoundation frameworks used by the Keychain backend:
 * the {@code SecItem} functions, the CoreFoundation types they take ({@code CFString},
 * {@code CFData}, {@code CFDictionary}), and the framework constants, read as data symbols. All
 * downcall descriptors are registered for native image in
 * {@code META-INF/native-image/de.planmarshall/pm-mcp-server-credentials/reachability-metadata.json}.
 */
final class MacSecurity {

    static final String SECURITY = "/System/Library/Frameworks/Security.framework/Security";
    static final String CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    /** {@code errSecSuccess}. */
    static final int SUCCESS = 0;
    /** {@code errSecItemNotFound}. */
    static final int ITEM_NOT_FOUND = -25_300;
    /** {@code errSecInteractionNotAllowed}: the keychain is locked and no UI may be shown. */
    static final int INTERACTION_NOT_ALLOWED = -25_308;
    /** {@code errSecAuthFailed}. */
    static final int AUTH_FAILED = -25_293;
    /** {@code errSecMissingEntitlement}: the data-protection keychain needs a signed entitlement. */
    static final int MISSING_ENTITLEMENT = -34_018;

    private static final int CF_STRING_ENCODING_UTF8 = 0x0800_0100;

    private final MethodHandle cfStringCreate;
    private final MethodHandle cfDataCreate;
    private final MethodHandle cfDictionaryCreateMutable;
    private final MethodHandle cfDictionarySetValue;
    private final MethodHandle cfRelease;
    private final MethodHandle cfDataGetLength;
    private final MethodHandle cfDataGetBytePtr;
    private final MethodHandle secItemAdd;
    private final MethodHandle secItemCopyMatching;
    private final MethodHandle secItemUpdate;
    private final MethodHandle secItemDelete;

    final MemorySegment kSecClass;
    final MemorySegment kSecClassGenericPassword;
    final MemorySegment kSecAttrService;
    final MemorySegment kSecAttrAccount;
    final MemorySegment kSecValueData;
    final MemorySegment kSecReturnData;
    final MemorySegment kSecMatchLimit;
    final MemorySegment kSecMatchLimitOne;
    final MemorySegment kSecUseDataProtectionKeychain;
    final MemorySegment kCFBooleanTrue;
    private final MemorySegment keyCallBacks;
    private final MemorySegment valueCallBacks;

    /**
     * Loads both frameworks and links every function.
     *
     * @throws SecretStoreException if a framework or symbol is missing (not macOS)
     */
    MacSecurity() {
        try {
            var linker = Linker.nativeLinker();
            var security = SymbolLookup.libraryLookup(SECURITY, Arena.global());
            var cf = SymbolLookup.libraryLookup(CORE_FOUNDATION, Arena.global());
            cfStringCreate = link(linker, cf, "CFStringCreateWithCString",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
            cfDataCreate = link(linker, cf, "CFDataCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
            cfDictionaryCreateMutable = link(linker, cf, "CFDictionaryCreateMutable",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
            cfDictionarySetValue = link(linker, cf, "CFDictionarySetValue",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
            cfRelease = link(linker, cf, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
            cfDataGetLength = link(linker, cf, "CFDataGetLength", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            cfDataGetBytePtr = link(linker, cf, "CFDataGetBytePtr", FunctionDescriptor.of(ADDRESS, ADDRESS));
            secItemAdd = link(linker, security, "SecItemAdd", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            secItemCopyMatching = link(linker, security, "SecItemCopyMatching",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            secItemUpdate = link(linker, security, "SecItemUpdate", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            secItemDelete = link(linker, security, "SecItemDelete", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            kSecClass = constant(security, "kSecClass");
            kSecClassGenericPassword = constant(security, "kSecClassGenericPassword");
            kSecAttrService = constant(security, "kSecAttrService");
            kSecAttrAccount = constant(security, "kSecAttrAccount");
            kSecValueData = constant(security, "kSecValueData");
            kSecReturnData = constant(security, "kSecReturnData");
            kSecMatchLimit = constant(security, "kSecMatchLimit");
            kSecMatchLimitOne = constant(security, "kSecMatchLimitOne");
            kSecUseDataProtectionKeychain = constant(security, "kSecUseDataProtectionKeychain");
            kCFBooleanTrue = constant(cf, "kCFBooleanTrue");
            keyCallBacks = symbol(cf, "kCFTypeDictionaryKeyCallBacks");
            valueCallBacks = symbol(cf, "kCFTypeDictionaryValueCallBacks");
        } catch (IllegalArgumentException e) {
            throw new SecretStoreException(SecretStoreException.Reason.FAILED, "Security.framework not available", e);
        }
    }

    private static MethodHandle link(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(symbol(lookup, name), descriptor);
    }

    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(
                () -> new SecretStoreException(SecretStoreException.Reason.FAILED, "symbol not found: " + name));
    }

    /** A data symbol holding a pointer ({@code extern const CFStringRef kSecClass;}): its value. */
    private static MemorySegment constant(SymbolLookup lookup, String name) {
        return symbol(lookup, name).reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    }

    /**
     * The CoreFoundation objects created in one operation, released together.
     */
    final class Scope implements AutoCloseable {

        private final List<MemorySegment> owned = new ArrayList<>();
        final Arena arena = Arena.ofConfined();

        /**
         * @param value a Java string
         * @return a new {@code CFStringRef}
         */
        MemorySegment string(String value) {
            var cString = arena.allocateFrom(value);
            return own(call(() -> (MemorySegment) cfStringCreate.invokeExact(MemorySegment.NULL, cString,
                    CF_STRING_ENCODING_UTF8)));
        }

        /**
         * @param bytes the bytes
         * @return a new {@code CFDataRef}
         */
        MemorySegment data(byte[] bytes) {
            var buffer = arena.allocate(Math.max(1, bytes.length));
            MemorySegment.copy(bytes, 0, buffer, JAVA_BYTE, 0, bytes.length);
            return own(call(() -> (MemorySegment) cfDataCreate.invokeExact(MemorySegment.NULL, buffer,
                    (long) bytes.length)));
        }

        /**
         * @param entries alternating keys and values, all CoreFoundation objects
         * @return a new mutable {@code CFDictionaryRef} retaining its entries
         */
        MemorySegment dictionary(MemorySegment... entries) {
            var dictionary = own(call(() -> (MemorySegment) cfDictionaryCreateMutable.invokeExact(MemorySegment.NULL,
                    0L, keyCallBacks, valueCallBacks)));
            for (int i = 0; i < entries.length; i += 2) {
                var key = entries[i];
                var value = entries[i + 1];
                call(() -> {
                    cfDictionarySetValue.invokeExact(dictionary, key, value);
                    return null;
                });
            }
            return dictionary;
        }

        /**
         * Takes ownership of a returned CoreFoundation object.
         *
         * @param ref the object
         * @return the object
         */
        MemorySegment own(MemorySegment ref) {
            owned.add(ref);
            return ref;
        }

        @Override
        public void close() {
            for (var ref : owned) {
                if (!MemorySegment.NULL.equals(ref)) {
                    call(() -> {
                        cfRelease.invokeExact(ref);
                        return null;
                    });
                }
            }
            arena.close();
        }
    }

    /** @return a new scope for one operation */
    Scope scope() {
        return new Scope();
    }

    int add(MemorySegment attributes) {
        return call(() -> (int) secItemAdd.invokeExact(attributes, MemorySegment.NULL));
    }

    int update(MemorySegment query, MemorySegment attributes) {
        return call(() -> (int) secItemUpdate.invokeExact(query, attributes));
    }

    int delete(MemorySegment query) {
        return call(() -> (int) secItemDelete.invokeExact(query));
    }

    /**
     * @param query  the query, returning data
     * @param result receives the {@code CFTypeRef} of the match
     * @return the {@code OSStatus}
     */
    int copyMatching(MemorySegment query, MemorySegment result) {
        return call(() -> (int) secItemCopyMatching.invokeExact(query, result));
    }

    /**
     * @param data a {@code CFDataRef}
     * @return its bytes as a UTF-8 string
     */
    String utf8(MemorySegment data) {
        long length = call(() -> (long) cfDataGetLength.invokeExact(data));
        var bytes = call(() -> (MemorySegment) cfDataGetBytePtr.invokeExact(data)).reinterpret(length)
                .toArray(JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A downcall; {@link MethodHandle#invokeExact} declares {@link Throwable}. */
    @FunctionalInterface
    private interface Downcall<T> {
        T call() throws Throwable;
    }

    @SuppressWarnings("java:S1181") // MethodHandle.invokeExact declares Throwable; Errors are rethrown
    private static <T> T call(Downcall<T> downcall) {
        try {
            return downcall.call();
            // cui-rewrite:disable InvalidExceptionUsageRecipe
        } catch (Throwable t) {
            if (t instanceof Error e) {
                throw e;
            }
            if (t instanceof SecretStoreException e) {
                throw e;
            }
            throw new SecretStoreException(SecretStoreException.Reason.FAILED, "Security.framework call failed", t);
        }
    }
}
