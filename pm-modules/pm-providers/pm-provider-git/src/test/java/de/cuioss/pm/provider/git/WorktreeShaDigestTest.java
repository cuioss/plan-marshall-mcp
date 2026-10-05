/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WorktreeShaDigest framing")
class WorktreeShaDigestTest {

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    /** One expected entry: path, tag and payload. */
    record Entry(String path, char tag, byte[] payload) {
    }

    static Entry file(String path, String content) {
        return new Entry(path, 'F', content.getBytes(StandardCharsets.UTF_8));
    }

    static Entry link(String path, String target) {
        return new Entry(path, 'L', target.getBytes(StandardCharsets.UTF_8));
    }

    static Entry deleted(String path) {
        return new Entry(path, 'D', null);
    }

    /** Independent computation of the version-1 framing, written from the specification text. */
    static String independentDigest(String head, List<Entry> entries) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(head.getBytes(StandardCharsets.US_ASCII));
        bytes.write(0);
        entries.stream().sorted(Comparator.comparing(Entry::path)).forEach(e -> {
            bytes.writeBytes(e.path().getBytes(StandardCharsets.UTF_8));
            bytes.write(0);
            bytes.write(e.tag());
            if (e.payload() != null) {
                long length = e.payload().length;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    bytes.write((int) (length >>> shift) & 0xFF);
                }
                bytes.writeBytes(e.payload());
            }
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("frames files, links and deletions sorted by path bytes")
    void frames(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("z.txt"), "zz");
        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("dir/a.txt"), "a");
        Files.createSymbolicLink(root.resolve("ln"), Path.of("dir/a.txt"));

        String digest = WorktreeShaDigest.digest(HEAD, root, List.of("z.txt", "ln", "gone.txt", "dir/a.txt"));

        assertEquals(independentDigest(HEAD, List.of(file("z.txt", "zz"), link("ln", "dir/a.txt"),
                deleted("gone.txt"), file("dir/a.txt", "a"))), digest);
    }

    @Test
    @DisplayName("has a fixed digest for an empty path set")
    void emptySet(@TempDir Path root) throws Exception {
        assertEquals(independentDigest(HEAD, List.of()), WorktreeShaDigest.digest(HEAD, root, List.of()));
    }
}
