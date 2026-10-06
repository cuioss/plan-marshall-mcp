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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


import de.cuioss.pm.api.MachinePaths;

/**
 * Startup step 3, directory and permission verification (PM-SEC-4).
 * <p>
 * Creates {@code run/}, {@code enrolments/} and {@code state/} where they are absent (mode {@code 0700}); an
 * existing directory is checked, never repaired. {@code <PM_MCP_BASE>}, {@code enrolments/} and {@code run/}
 * must be owned by the running user with mode {@code 0700} and must not be symlinks; every file of
 * {@code state/} must have mode {@code 0600}. The socket path must fit the platform's {@code sun_path}
 * limit; that check runs first, so a refused start creates no directory.
 *
 * @param paths the machine paths
 * @param user  the name of the running user, the expected owner
 * @since 0.1
 */
public record BaseDirectoryCheck(MachinePaths paths, String user) {

    /** Directory name of the enrolment store below the base. */
    static final String ENROLMENTS = "enrolments";

    /**
     * Runs the step.
     *
     * @return the first violation as a diagnostic naming the path, or empty when the base is in order
     * @throws IOException if a directory cannot be created or inspected
     */
    public Optional<String> run() throws IOException {
        if (!paths.socketPathFits()) {
            return Optional.of(("socket path %s is %d bytes and exceeds the sun_path limit of %d bytes; "
                    + "choose a shorter PM_MCP_BASE").formatted(paths.socket(), paths.socketPathBytes(),
                    paths.os().sunPathLimit() - 1));
        }
        var enrolments = paths.base().resolve(ENROLMENTS);
        for (var dir : List.of(paths.runDir(), enrolments, paths.stateDir())) {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(dir, PosixModes.directoryAttribute());
            }
        }
        var violations = new ArrayList<String>();
        for (var dir : List.of(paths.base(), enrolments, paths.runDir())) {
            checkPrivate(dir, true).ifPresent(violations::add);
        }
        try (var files = Files.list(paths.stateDir())) {
            for (var file : files.toList()) {
                checkPrivate(file, Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)).ifPresent(violations::add);
            }
        }
        return violations.stream().findFirst();
    }

    private Optional<String> checkPrivate(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path)) {
            return Optional.of("%s is a symbolic link".formatted(path));
        }
        var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
        if (!owner.equals(user)) {
            return Optional.of("%s is owned by %s, not by %s".formatted(path, owner, user));
        }
        var expected = directory ? PosixModes.DIRECTORY : PosixModes.FILE;
        var actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!actual.equals(expected)) {
            return Optional.of("%s has mode %s, expected %s".formatted(path, PosixModes.octal(actual),
                    PosixModes.octal(expected)));
        }
        return Optional.empty();
    }
}
