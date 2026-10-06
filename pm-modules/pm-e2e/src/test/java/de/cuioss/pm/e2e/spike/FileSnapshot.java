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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sizes and modification times of every regular file below a set of roots, to find the files a harness wrote:
 * a walk before and after the run, compared. Bounded in depth and entries.
 *
 * @param files     the files by path: {@code size:mtime}
 * @param truncated whether a bound cut the walk short
 */
record FileSnapshot(Map<Path, String> files, boolean truncated) {

    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILES = 200_000;

    /**
     * @param roots the roots (files or directories); absent ones are skipped
     * @return the snapshot
     */
    static FileSnapshot of(List<Path> roots) {
        var files = new HashMap<Path, String>();
        var truncated = new boolean[1];
        for (var root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (files.size() >= MAX_FILES) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        if (attributes.isRegularFile()) {
                            files.put(file, attributes.size() + ":" + attributes.lastModifiedTime().toMillis());
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException _) {
                truncated[0] = true;
            }
        }
        return new FileSnapshot(Map.copyOf(files), truncated[0]);
    }

    /**
     * @param after the later snapshot
     * @return the files created or changed since this snapshot, sorted
     */
    List<Path> changedIn(FileSnapshot after) {
        var changed = new TreeSet<Path>();
        after.files.forEach((path, stamp) -> {
            if (!stamp.equals(files.get(path))) {
                changed.add(path);
            }
        });
        return new ArrayList<>(changed);
    }

    /**
     * @param after the later snapshot
     * @return the files removed since this snapshot, sorted
     */
    List<Path> removedIn(FileSnapshot after) {
        var removed = new TreeSet<Path>();
        files.keySet().forEach(path -> {
            if (!after.files.containsKey(path)) {
                removed.add(path);
            }
        });
        return new ArrayList<>(removed);
    }
}
