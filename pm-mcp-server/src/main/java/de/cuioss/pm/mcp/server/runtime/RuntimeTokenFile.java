/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * The runtime token file {@code <PM_MCP_BASE>/run/runtime.token} (startup step 5).
 * <p>
 * A fresh token is 256 bits from {@link SecureRandom}, base64url-encoded without padding, written to a
 * {@code .tmp} sibling with mode {@code 0600}, forced to disk, and moved into place atomically.
 *
 * @param file the token file
 * @since 0.1
 */
public record RuntimeTokenFile(Path file) {

    /** Token length in bytes before encoding. */
    static final int TOKEN_BYTES = 32;

    /**
     * Generates a fresh token and writes it atomically, replacing the previous instance's token.
     *
     * @param random the source of randomness
     * @return the written token
     * @throws IOException if the token cannot be written
     */
    public String writeFresh(SecureRandom random) throws IOException {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        try (var channel = FileChannel.open(tmp, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                     PosixModes.fileAttribute())) {
            var buffer = ByteBuffer.wrap(token.getBytes(StandardCharsets.US_ASCII));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return token;
    }

    /**
     * @return the token the file holds
     * @throws IOException if the file cannot be read
     */
    public String read() throws IOException {
        return Files.readString(file, StandardCharsets.US_ASCII).strip();
    }
}
