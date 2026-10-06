/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * Short private machine roots for tests: {@code /tmp/<prefix>-<8 hex>}, so that the socket path stays far below
 * the {@code sun_path} limit. The directory itself is not created: the runtime creates it.
 */
public final class TestBases {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestBases() {
    }

    /**
     * @param prefix a short prefix
     * @return a fresh, not yet existing base path
     */
    public static Path create(String prefix) {
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return Path.of("/tmp", prefix + "-" + HexFormat.of().formatHex(bytes));
    }

    /**
     * Deletes a base recursively; a missing base is fine.
     *
     * @param base the base
     */
    public static void delete(Path base) {
        if (base == null || !Files.exists(base)) {
            return;
        }
        try (var walk = Files.walk(base)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
