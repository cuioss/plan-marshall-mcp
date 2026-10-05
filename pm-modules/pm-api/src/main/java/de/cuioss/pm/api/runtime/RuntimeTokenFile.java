/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

import de.cuioss.pm.api.MachinePaths;

/**
 * The client side of the runtime token: before reading, it verifies with {@code lstat} semantics
 * ({@link LinkOption#NOFOLLOW_LINKS}) that {@code run/} and {@code run/runtime.token} are no
 * symbolic links, are owned by the effective user, and have the modes {@code 0700} and
 * {@code 0600}; then it reads the token without following a link.
 */
public final class RuntimeTokenFile {

    /** Mode {@code 0700}. */
    public static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");

    /** Mode {@code 0600}. */
    public static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");

    private static final int MAX_TOKEN_BYTES = 1024;

    private final MachinePaths paths;
    private final int effectiveUserId;

    /**
     * @param paths           the machine paths
     * @param effectiveUserId the effective user id of this process
     */
    public RuntimeTokenFile(MachinePaths paths, int effectiveUserId) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.effectiveUserId = effectiveUserId;
    }

    /**
     * Checks both paths and reads the token.
     *
     * @return the token, surrounding whitespace removed
     * @throws java.nio.file.NoSuchFileException if {@code run/} or the token file is absent
     * @throws InsecureRuntimeFileException      if a check fails
     * @throws IOException                       on a read failure
     */
    public String read() throws IOException {
        verify(paths.runDir(), true, effectiveUserId);
        var token = paths.runtimeToken();
        verify(token, false, effectiveUserId);
        try (var channel = Files.newByteChannel(token, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            var buffer = ByteBuffer.allocate(MAX_TOKEN_BYTES + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // read up to the bound
            }
            if (buffer.position() > MAX_TOKEN_BYTES) {
                throw new InsecureRuntimeFileException(token, "exceeds " + MAX_TOKEN_BYTES + " bytes");
            }
            var value = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).strip();
            if (value.isEmpty()) {
                throw new InsecureRuntimeFileException(token, "is empty");
            }
            return value;
        }
    }

    /**
     * Verifies owner, mode, type, and the absence of a symbolic link.
     *
     * @param path            the path
     * @param directory       {@code true} for {@code run/}, {@code false} for the token file
     * @param effectiveUserId the expected owner
     * @throws java.nio.file.NoSuchFileException if the path is absent
     * @throws InsecureRuntimeFileException      if a check fails
     * @throws IOException                       on a read failure of the attributes
     */
    static void verify(Path path, boolean directory, int effectiveUserId) throws IOException {
        var attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            throw new InsecureRuntimeFileException(path, "is a symbolic link");
        }
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
            throw new InsecureRuntimeFileException(path, directory ? "is no directory" : "is no regular file");
        }
        var owner = (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        if (owner != effectiveUserId) {
            throw new InsecureRuntimeFileException(path,
                    "is owned by uid " + owner + ", not by the effective user " + effectiveUserId);
        }
        var expected = directory ? DIRECTORY_MODE : FILE_MODE;
        if (!attributes.permissions().equals(expected)) {
            throw new InsecureRuntimeFileException(path,
                    "has mode " + octal(attributes.permissions()) + ", expected " + octal(expected));
        }
    }

    static String octal(Set<PosixFilePermission> permissions) {
        var mode = 0;
        for (var permission : permissions) {
            mode |= 1 << 8 - permission.ordinal();
        }
        return String.format("%04o", mode);
    }
}
