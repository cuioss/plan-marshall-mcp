/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * Builds the Landlock read set: the whole filesystem except {@code <PM_MCP_BASE>}.
 * <p>
 * Landlock rules only grant access, so a rule on {@code /} would also grant the base. The read set
 * is therefore one rule per entry of every directory from the root down to the parent of the base,
 * leaving out the next directory on that path (doc/specification/job-runtime/
 * 02-confinement-and-environment.adoc, Read set). An entry whose real path (symbolic links
 * resolved) lies inside the base or contains it is left out as well, since the kernel attaches a
 * rule to the inode the link points to. Entries created after enumeration are not readable.
 * The directories on the path themselves get no rule, so a job cannot list them.
 */
@UtilityClass
class ReadSet {

    /**
     * Enumerates the read set.
     *
     * @param root the filesystem root, {@code /} in production, a temporary directory in tests; real path
     * @param base the denied directory, canonical (see {@link #canonical(Path)}) and below the root
     * @return the paths that get a read rule, in enumeration order
     * @throws UsageException if the base is not strictly below the root
     */
    static List<Path> enumerate(Path root, Path base) throws UsageException {
        if (!base.startsWith(root) || base.equals(root)) {
            throw new UsageException("denied base " + base + " is not below " + root);
        }
        var relative = root.relativize(base);
        var result = new ArrayList<Path>();
        var directory = root;
        for (int i = 0; i < relative.getNameCount(); i++) {
            var next = directory.resolve(relative.getName(i));
            for (var entry : list(directory)) {
                if (!entry.equals(next) && !overlaps(entry, base)) {
                    result.add(entry);
                }
            }
            directory = next;
        }
        return result;
    }

    /**
     * Canonicalizes a path whose tail may not exist yet: the longest existing prefix is resolved to
     * its real path and the rest appended.
     *
     * @param path an absolute, normalized path
     * @return the canonical path
     */
    static Path canonical(Path path) {
        var existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(path));
        } catch (IOException _) {
            return path;
        }
    }

    private static List<Path> list(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.sorted().toList();
        } catch (IOException _) {
            // Missing or unlistable directory: none of its entries becomes readable (fail closed).
            return List.of();
        }
    }

    private static boolean overlaps(Path entry, Path base) {
        Path real;
        try {
            real = entry.toRealPath();
        } catch (IOException _) {
            // Dangling or unreachable: the kernel could not open it either.
            return true;
        }
        return real.startsWith(base) || base.startsWith(real);
    }
}
