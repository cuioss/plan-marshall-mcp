/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


import lombok.experimental.UtilityClass;

/**
 * Hashing and constant-time comparison of presented secrets.
 *
 * @since 0.1
 */
@UtilityClass
public final class Secrets {

    /**
     * @param secret a presented secret
     * @return its SHA-256 over the UTF-8 bytes
     */
    public static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    /**
     * Compares in time independent of where the inputs differ ({@link MessageDigest#isEqual}).
     *
     * @param expected the expected bytes
     * @param presented the presented bytes
     * @return {@code true} if both are equal
     */
    public static boolean constantTimeEquals(byte[] expected, byte[] presented) {
        return MessageDigest.isEqual(expected, presented);
    }
}
