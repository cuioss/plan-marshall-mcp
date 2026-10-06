/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

/**
 * The per-worker OpenCode data directory: every OpenCode worker gets its own {@code XDG_DATA_HOME} inside its
 * worker directory, so its SQLite database {@code opencode.db}, its log, its snapshots and its saved tool output
 * never touch the operator's {@code ~/.local/share/opencode}. A stalled worker then holds only its own database
 * lock, not the one every OpenCode process of the user shares.
 * <p>
 * OpenCode reads its login from {@code <XDG_DATA_HOME>/opencode/auth.json}. At launch the operator's login file is
 * copied there with mode {@code 0600} (directories {@code 0700}); when the worker ends the copy is deleted.
 * OpenCode's other paths keep their defaults. Two stay in the Landlock write set: {@code ~/.cache/opencode} (the
 * model catalogue {@code models.json}, refreshed when stale) and {@code ~/.local/state/opencode} (the lock
 * directory {@code locks/}, prompt history, model choice). {@code ~/.config/opencode} leaves the write set: a worker
 * only reads its harness configuration and never changes what the operator's next session loads.
 * <p>
 * {@code -Dspike.opencode.shared-data=true} restores the shared {@code ~/.local/share/opencode} of the first Linux
 * run.
 */
final class OpencodeData {

    /** OpenCode's data directory below {@code XDG_DATA_HOME}'s default {@code ~/.local/share}. */
    static final String SHARED_DATA = ".local/share/opencode";
    /** The login file in the data directory. */
    static final String AUTH = "auth.json";
    /** OpenCode's configuration directory, read-only for a worker with its own data directory. */
    static final String CONFIG = ".config/opencode";
    /** The per-worker {@code XDG_DATA_HOME}, relative to the worker directory. */
    static final String DATA_HOME = "xdg-data";

    private OpencodeData() {
    }

    /**
     * The operator's login file: {@code $XDG_DATA_HOME/opencode/auth.json} when the driver's environment sets
     * {@code XDG_DATA_HOME}, otherwise {@code ~/.local/share/opencode/auth.json}.
     *
     * @param environment the driver's environment
     * @param home        the user's home
     * @return the path of the operator's login file (it may not exist)
     */
    static Path operatorLogin(Map<String, String> environment, Path home) {
        var dataHome = environment.get("XDG_DATA_HOME");
        var data = dataHome == null || dataHome.isBlank() ? home.resolve(SHARED_DATA)
                : Path.of(dataHome).resolve("opencode");
        return data.resolve(AUTH);
    }

    /**
     * Creates {@code <workerDir>/xdg-data/opencode} and copies the operator's login into it with mode {@code 0600}.
     *
     * @param workerDir     the worker directory
     * @param operatorLogin the operator's login file; when it is missing, no login is copied and OpenCode exits
     *                      unauthenticated (a start failure)
     * @return the worker's {@code XDG_DATA_HOME}
     * @throws IOException if the directory or the copy cannot be written
     */
    static Path prepare(Path workerDir, Path operatorLogin) throws IOException {
        var dataHome = workerDir.resolve(DATA_HOME);
        var data = dataHome.resolve("opencode");
        var directory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        if (!Files.isDirectory(dataHome)) {
            Files.createDirectory(dataHome, directory);
        }
        if (!Files.isDirectory(data)) {
            Files.createDirectory(data, directory);
        }
        if (Files.isRegularFile(operatorLogin)) {
            var login = data.resolve(AUTH);
            Files.deleteIfExists(login);
            Files.createFile(login, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(login, Files.readAllBytes(operatorLogin));
        }
        return dataHome;
    }

    /**
     * Deletes the worker's copy of the login.
     *
     * @param dataHome the worker's {@code XDG_DATA_HOME}
     * @return whether a copy was deleted
     */
    static boolean removeLogin(Path dataHome) {
        try {
            return Files.deleteIfExists(dataHome.resolve("opencode").resolve(AUTH));
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * @param writeSet the write set with the harness's state paths
     * @param home     the user's home
     * @return the write set without the shared data directory and the configuration directory
     */
    static List<Path> perWorkerWriteSet(List<Path> writeSet, Path home) {
        var removed = List.of(home.resolve(SHARED_DATA), home.resolve(CONFIG));
        return writeSet.stream().filter(path -> !removed.contains(path)).toList();
    }

    /**
     * @param harness the harness
     * @param shared  {@code spike.opencode.shared-data}
     * @return the per-worker data directory applies
     */
    static boolean applies(Harness harness, boolean shared) {
        return harness == Harness.OPENCODE && !shared;
    }
}
