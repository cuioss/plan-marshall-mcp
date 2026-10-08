/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Set;

/**
 * The runtime singleton lock {@code <PM_MCP_BASE>/locks/runtime.lock}, held for the lifetime of the process
 * (startup step 1).
 * <p>
 * The lock is taken with {@link FileChannel#tryLock()}: a second runtime for the same base fails to acquire it
 * and must exit without writing anything. Opening an existing lock file changes neither its content nor its
 * modification time.
 * <p>
 * The holder owns the channel: the lock is released when the holder is closed (the entry point closes it on
 * shutdown) or when the process ends.
 *
 * @since 0.1
 */
public final class RuntimeLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private RuntimeLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Tries to acquire the singleton lock. Creates the base and the {@code locks/} directory (mode {@code 0700})
     * and the lock file (mode {@code 0600}) where they are absent.
     *
     * @param lockFile the lock file {@code <PM_MCP_BASE>/locks/runtime.lock}
     * @return the held lock, or empty when another process holds it
     * @throws IOException if the lock file cannot be opened
     */
    public static Optional<RuntimeLock> tryAcquire(Path lockFile) throws IOException {
        var dir = lockFile.getParent();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir, PosixModes.directoryAttribute());
        }
        var channel = open(lockFile);
        try {
            var lock = tryLock(channel);
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new RuntimeLock(channel, lock));
        } catch (IOException e) {
            closeAfterFailure(channel, e);
            throw e;
        }
    }

    // Opening an existing lock file must change neither its content nor its modification time.
    private static FileChannel open(Path lockFile) throws IOException {
        if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            return FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
        return FileChannel.open(lockFile, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PosixModes.fileAttribute());
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException _) {
            return null;
        }
    }

    private static void closeAfterFailure(FileChannel channel, IOException failure) {
        try {
            channel.close();
        } catch (IOException suppressed) {
            failure.addSuppressed(suppressed);
        }
    }

    /**
     * @return {@code true} while the lock is held
     */
    public boolean isHeld() {
        return lock.isValid();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * Releases the lock after a failed startup step, recording a failure to close on that step's exception.
     *
     * @param failure the exception of the failed step
     */
    void closeAfterFailure(IOException failure) {
        closeAfterFailure(channel, failure);
    }
}
