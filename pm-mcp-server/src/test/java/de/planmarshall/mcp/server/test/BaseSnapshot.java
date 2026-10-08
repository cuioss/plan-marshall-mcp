/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * A byte-level snapshot of a machine root: per entry its kind, mode, size, modification time and, for regular
 * files, the SHA-256 of the content.
 */
public final class BaseSnapshot {

    private BaseSnapshot() {
    }

    /**
     * @param base the machine root
     * @return the snapshot, keyed by relative path
     * @throws IOException if an entry cannot be read
     */
    public static Map<String, String> of(Path base) throws IOException {
        var snapshot = new TreeMap<String, String>();
        try (var walk = Files.walk(base)) {
            for (var path : walk.toList()) {
                var attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                var kind = attributes.isDirectory() ? "dir" : attributes.isRegularFile() ? "file" : "other";
                var entry = kind + " " + PosixFilePermissions.toString(attributes.permissions()) + " "
                        + attributes.size() + " " + attributes.lastModifiedTime().toMillis();
                if (attributes.isRegularFile()) {
                    entry += " " + sha256(Files.readAllBytes(path));
                }
                snapshot.put(base.relativize(path).toString(), entry);
            }
        }
        return snapshot;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
