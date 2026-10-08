/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.start;

import java.io.IOException;
import java.nio.file.Files;
import java.security.SecureRandom;


import de.planmarshall.api.MachinePaths;

/**
 * Steps 1 to 5 of the runtime's startup sequence, run before the HTTP server binds the socket
 * (doc/specification/runtime-model/02-startup-recovery-drain.adoc, Startup Sequence).
 * <ol>
 * <li>Acquire the singleton lock; a runtime that loses writes nothing.</li>
 * <li>Remove a stale {@code run/runtime.sock} and {@code state/runtime.json}, only while holding the lock.</li>
 * <li>Check the socket path length, create and verify the directories ({@link BaseDirectoryCheck}).</li>
 * <li>Bounded verification: nothing yet (no audit chain exists).</li>
 * <li>Write a fresh runtime token.</li>
 * </ol>
 * Steps 6 (bind, runtime record) and 7 (ready) run inside Quarkus ({@link SocketBinding}).
 *
 * @param paths the machine paths
 * @param user  the name of the running user, the expected owner of the base
 * @since 0.1
 */
public record StartupSequence(MachinePaths paths, String user) {

    /**
     * The result of the steps before the bind.
     */
    public sealed interface Outcome {

        /**
         * The runtime holds the lock, the base is verified and the token written.
         *
         * @param lock the held singleton lock
         */
        record Ready(RuntimeLock lock) implements Outcome {
        }

        /** Another runtime holds the singleton lock; nothing was written. */
        record LockHeld() implements Outcome {
        }

        /**
         * A permission violation or an over-long socket path refuses the start (exit code 77).
         *
         * @param diagnostic the diagnostic naming the path
         */
        record Refused(String diagnostic) implements Outcome {
        }
    }

    /** Exit code of a refused start ({@code EX_NOPERM}). */
    public static final int EXIT_REFUSED = 77;

    /**
     * Runs steps 1 to 5.
     *
     * @param random the source of the runtime token
     * @return the outcome; on {@link Outcome.Ready} the caller keeps the lock for the process lifetime
     * @throws IOException if a file operation fails
     */
    public Outcome run(SecureRandom random) throws IOException {
        var acquired = RuntimeLock.tryAcquire(paths.runtimeLock());
        if (acquired.isEmpty()) {
            return new Outcome.LockHeld();
        }
        var lock = acquired.get();
        try {
            Files.deleteIfExists(paths.socket());
            Files.deleteIfExists(paths.runtimeRecord());
            var violation = new BaseDirectoryCheck(paths, user).run();
            if (violation.isPresent()) {
                lock.close();
                return new Outcome.Refused(violation.get());
            }
            new RuntimeTokenFile(paths.runtimeToken()).writeFresh(random);
            return new Outcome.Ready(lock);
        } catch (IOException e) {
            lock.closeAfterFailure(e);
            throw e;
        }
    }
}
