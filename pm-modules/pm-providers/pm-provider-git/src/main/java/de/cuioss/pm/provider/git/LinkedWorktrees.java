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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;

import de.cuioss.pm.provider.git.GitOperations.Worktree;

/**
 * The linked-worktree administration files of git, read and written natively, since JGit has no
 * {@code git worktree} command. A linked worktree {@code <name>} consists of
 * {@code <common>/worktrees/<name>/} holding {@code gitdir} (the absolute path of the worktree's
 * {@code .git} file), {@code commondir} ({@code ../..}), {@code HEAD}, its index, and an optional
 * {@code locked} file, and of the worktree's {@code .git} file pointing back with
 * {@code gitdir: <admin dir>}. The layout is git's own, so {@code git worktree list} reads it.
 *
 * @since 0.1
 */
final class LinkedWorktrees {

    static final String WORKTREES = "worktrees";
    static final String LOCKED = "locked";
    private static final String COMMONDIR = "commondir";
    private static final String REF_PREFIX = "ref: ";

    private LinkedWorktrees() {
    }

    /**
     * Writes the administration files of a new linked worktree, locked while initializing.
     *
     * @param commonDir the common git directory
     * @param worktree  the canonical worktree root (must exist)
     * @param head      the content of {@code HEAD}: a full ref name or a commit id
     * @return the administration directory
     * @throws IOException if a file cannot be written
     */
    static Path create(Path commonDir, Path worktree, String head) throws IOException {
        Path admin = uniqueAdminDir(commonDir.resolve(WORKTREES), worktree.getFileName().toString());
        Files.createDirectories(admin);
        write(admin.resolve(LOCKED), "initializing");
        write(admin.resolve(Constants.GITDIR_FILE), worktree.resolve(Constants.DOT_GIT).toString());
        write(admin.resolve(COMMONDIR), "../..");
        write(admin.resolve(Constants.HEAD), head.startsWith(Constants.R_REFS) ? REF_PREFIX + head : head);
        write(worktree.resolve(Constants.DOT_GIT), "gitdir: " + admin);
        return admin;
    }

    /**
     * Lists the linked worktrees of a common git directory, ordered by name.
     *
     * @param commonDir the common git directory
     * @return the linked worktrees
     * @throws IOException if the administration files cannot be read
     */
    static List<Worktree> list(Path commonDir) throws IOException {
        Path base = commonDir.resolve(WORKTREES);
        var result = new ArrayList<Worktree>();
        if (!Files.isDirectory(base)) {
            return result;
        }
        var admins = new ArrayList<Path>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(base, Files::isDirectory)) {
            entries.forEach(admins::add);
        }
        admins.sort(null);
        for (Path admin : admins) {
            Path gitFile = Path.of(read(admin.resolve(Constants.GITDIR_FILE)));
            String head = read(admin.resolve(Constants.HEAD));
            Optional<String> branch = head.startsWith(REF_PREFIX)
                    ? Optional.of(shortBranch(head.substring(REF_PREFIX.length())))
                    : Optional.empty();
            Optional<String> id = ObjectId.isId(head) ? Optional.of(head) : Optional.empty();
            result.add(new Worktree(gitFile.getParent(), Optional.of(admin.getFileName().toString()), branch, id, false,
                    Files.exists(admin.resolve(LOCKED)), !Files.exists(gitFile)));
        }
        return result;
    }

    /**
     * Deletes a worktree directory (never following symbolic links) and its administration directory.
     *
     * @param commonDir the common git directory
     * @param name      the administrative name
     * @param worktree  the worktree root
     * @throws IOException if a file cannot be deleted
     */
    static void delete(Path commonDir, String name, Path worktree) throws IOException {
        deleteTree(worktree);
        deleteTree(commonDir.resolve(WORKTREES).resolve(name));
    }

    /**
     * Removes the initialization lock.
     *
     * @param admin the administration directory
     * @throws IOException if the lock cannot be deleted
     */
    static void unlock(Path admin) throws IOException {
        Files.deleteIfExists(admin.resolve(LOCKED));
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String shortBranch(String ref) {
        return ref.startsWith(Constants.R_HEADS) ? ref.substring(Constants.R_HEADS.length()) : ref;
    }

    private static Path uniqueAdminDir(Path base, String name) {
        Path candidate = base.resolve(name);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = base.resolve(name + suffix++);
        }
        return candidate;
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content + "\n", StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).trim();
    }
}
