/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.provider.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * The framing of {@code git.worktree-sha} version 1 over a path set (job runtime specification,
 * Working-Tree Hash, rule 2): {@code sha256(HEAD ‖ NUL ‖ Σ entry)} with the entries sorted by the
 * bytes of their UTF-8 path; an entry is the path, {@code NUL}, and {@code F} with the 8-byte
 * big-endian length and the file bytes, {@code L} with the length and the link target, or {@code D}
 * for a path that no longer exists.
 *
 * @since 0.1
 */
final class WorktreeShaDigest {

    private static final byte NUL = 0;
    private static final int BUFFER = 64 * 1024;

    private WorktreeShaDigest() {
    }

    /**
     * @param head  the lower-case hex {@code HEAD} commit id
     * @param root  the worktree root the paths are relative to
     * @param paths the changed, deleted and untracked paths ({@code /}-separated)
     * @return the lower-case hex digest
     * @throws IOException if a file cannot be read
     */
    static String digest(String head, Path root, Collection<String> paths) throws IOException {
        MessageDigest sha = sha256();
        sha.update(head.getBytes(StandardCharsets.US_ASCII));
        sha.update(NUL);
        List<byte[]> sorted = paths.stream().map(p -> p.getBytes(StandardCharsets.UTF_8))
                .sorted(Arrays::compareUnsigned).toList();
        for (byte[] path : sorted) {
            sha.update(path);
            sha.update(NUL);
            Path file = root.resolve(new String(path, StandardCharsets.UTF_8));
            if (Files.isSymbolicLink(file)) {
                byte[] target = Files.readSymbolicLink(file).toString().getBytes(StandardCharsets.UTF_8);
                sha.update((byte) 'L');
                sha.update(length(target.length));
                sha.update(target);
            } else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                sha.update((byte) 'F');
                sha.update(length(Files.size(file)));
                try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[BUFFER];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        sha.update(buffer, 0, read);
                    }
                }
            } else {
                sha.update((byte) 'D');
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static byte[] length(long length) {
        return ByteBuffer.allocate(Long.BYTES).putLong(length).array();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
