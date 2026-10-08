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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;

import de.planmarshall.provider.git.GitOperations.CommitInfo;
import de.planmarshall.provider.git.GitOperations.CommitInput;
import de.planmarshall.provider.git.GitOperations.GitCredential;
import de.planmarshall.provider.git.GitOperations.Identity;
import de.planmarshall.provider.git.GitOperations.PushInput;
import de.planmarshall.provider.git.GitOperations.RemoteInput;
import de.planmarshall.provider.git.GitOperations.Worktree;
import de.planmarshall.provider.git.GitOperations.WorktreeAddInput;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("JGitOperations (native git variant)")
class JGitOperationsTest {

    private static final Identity AUTHOR = new Identity("Operator", "operator@example.com");
    private static final Identity AGENT = new Identity("pm-agent[bot]", "1+pm-agent[bot]@users.noreply.github.com");

    @TempDir
    Path temp;

    private JGitOperations git;
    private Path repo;

    @BeforeEach
    void initRepository() throws IOException {
        git = new JGitOperations(RemotePolicy.localOnly());
        repo = temp.resolve("repo");
        assertTrue(git.init(repo, "main").isOk());
        write("README.md", "hello\n");
        commit("initial");
    }

    private void write(String path, String content) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private CommitInfo commit(String message) {
        return commitIn(repo, message);
    }

    private CommitInfo commitIn(Path root, String message) {
        GitResult<CommitInfo> result = git.commit(new CommitInput(root, message, AUTHOR, AGENT));
        assertEquals(GitOutcome.OK, result.outcome(), result.detail());
        return result.value().orElseThrow();
    }

    private String sha(Path root) {
        GitResult<GitOperations.WorktreeSha> result = git.worktreeSha(root);
        assertEquals(GitOutcome.OK, result.outcome(), result.detail());
        assertEquals(1, result.value().orElseThrow().version());
        return result.value().orElseThrow().digest();
    }

    @Nested
    @DisplayName("open, commit and log")
    class OpenCommitLog {

        @Test
        @DisplayName("opens a repository and reports its facts")
        void opens() throws Exception {
            var info = git.open(repo).value().orElseThrow();

            assertEquals(repo.toRealPath(), info.worktreeRoot());
            assertEquals(Optional.of("main"), info.branch());
            assertTrue(info.head().isPresent());
            assertFalse(info.linked());
            assertEquals(info.gitDir(), info.commonDir());
        }

        @Test
        @DisplayName("reports a directory outside any repository")
        void notARepository() throws Exception {
            Path plain = Files.createDirectories(temp.resolve("plain"));

            assertEquals(GitOutcome.NOT_A_REPOSITORY, git.open(plain).outcome());
            assertEquals(GitOutcome.NOT_A_REPOSITORY, git.log(plain, "HEAD", 1).outcome());
            assertEquals(GitOutcome.NOT_A_REPOSITORY,
                    git.commit(new CommitInput(plain, "m", AUTHOR, AGENT)).outcome());
            assertEquals(GitOutcome.NOT_A_REPOSITORY, git.worktreeList(plain).outcome());
        }

        @Test
        @DisplayName("commits additions and deletions with author and committer, unsigned")
        void commitsAllChanges() throws Exception {
            write("src/A.java", "class A {}\n");
            Files.delete(repo.resolve("README.md"));

            CommitInfo commit = commit("feat: add A\n\nbody line\n");

            assertEquals(AUTHOR, commit.author());
            assertEquals(AGENT, commit.committer());
            assertEquals("feat: add A\n\nbody line\n", commit.message());
            assertEquals(1, commit.parents().size());
            assertFalse(Files.exists(repo.resolve("README.md")));
            assertTrue(git.worktreeSha(repo).isOk());
        }

        @Test
        @DisplayName("reports nothing to commit")
        void nothingToCommit() {
            var result = git.commit(new CommitInput(repo, "empty", AUTHOR, AGENT));

            assertEquals(GitOutcome.NOTHING_TO_COMMIT, result.outcome());
        }

        @Test
        @DisplayName("walks the history newest first, bounded by the count")
        void logs() throws Exception {
            write("a.txt", "a");
            CommitInfo second = commit("second");
            write("b.txt", "b");
            CommitInfo third = commit("third");

            var all = git.log(repo, "HEAD", 10).value().orElseThrow();
            var one = git.log(repo, "main", 1).value().orElseThrow();

            assertEquals(List.of(third.id(), second.id()), all.subList(0, 2).stream().map(CommitInfo::id).toList());
            assertEquals(3, all.size());
            assertEquals(List.of(third), one);
            assertEquals(GitOutcome.UNKNOWN_REVISION, git.log(repo, "no-such-branch", 1).outcome());
        }
    }

    @Nested
    @DisplayName("git.worktree-sha")
    class WorktreeShaTests {

        @Test
        @DisplayName("is stable for the same tree and changes with every input of rule 1")
        void equalityBehaviour() throws Exception {
            String clean = sha(repo);
            assertEquals(clean, sha(repo));

            write("README.md", "changed\n");
            String modified = sha(repo);
            assertNotEquals(clean, modified);

            write("README.md", "hello\n");
            assertEquals(clean, sha(repo), "restoring the content restores the digest");

            write("new file ä.txt", "x");
            String untracked = sha(repo);
            assertNotEquals(clean, untracked);
            write("new file ä.txt", "y");
            assertNotEquals(untracked, sha(repo), "untracked content counts");
            Files.delete(repo.resolve("new file ä.txt"));

            Files.delete(repo.resolve("README.md"));
            String deleted = sha(repo);
            assertNotEquals(clean, deleted);
            write("README.md", "hello\n");

            write("c.txt", "c");
            commit("c");
            assertNotEquals(clean, sha(repo), "a new HEAD changes the digest");
        }

        @Test
        @DisplayName("counts staged-only changes and ignores ignored files")
        void stagedAndIgnored() throws Exception {
            String clean = sha(repo);
            write(".gitignore", "*.log\n");
            commit("ignore logs");
            String base = sha(repo);
            write("build.log", "noise");
            assertEquals(base, sha(repo), "ignored paths are not inputs");

            write("README.md", "staged\n");
            try (Git jgit = Git.open(repo.toFile())) {
                jgit.add().addFilepattern("README.md").call();
            }
            write("README.md", "hello\n");
            assertNotEquals(base, sha(repo), "a staged-only change is an input");
            assertNotEquals(clean, base);
        }

        @Test
        @DisplayName("matches an independent computation of the version-1 framing")
        void framing() throws Exception {
            write("b.txt", "B");
            write("a.txt", "AA");
            Files.createSymbolicLink(repo.resolve("link"), Path.of("a.txt"));
            String head = git.open(repo).value().orElseThrow().head().orElseThrow();

            String expected = WorktreeShaDigestTest.independentDigest(head, List.of(
                    WorktreeShaDigestTest.file("a.txt", "AA"), WorktreeShaDigestTest.file("b.txt", "B"),
                    WorktreeShaDigestTest.link("link", "a.txt")));

            assertEquals(expected, sha(repo));
        }

        @Test
        @DisplayName("is unaffected by configuration that shapes textual diff output")
        void configurationIndependent() throws Exception {
            write("README.md", "changed\n");
            String before = sha(repo);
            try (Git jgit = Git.open(repo.toFile())) {
                StoredConfig config = jgit.getRepository().getConfig();
                config.setBoolean("diff", null, "noprefix", true);
                config.setString("diff", null, "renames", "copies");
                config.setBoolean("core", null, "quotePath", false);
                config.save();
            }

            assertEquals(before, sha(repo));
        }

        @Test
        @DisplayName("is unavailable for an unborn HEAD or a missing repository")
        void unavailable() throws Exception {
            Path unborn = temp.resolve("unborn");
            git.init(unborn, "main");
            Path plain = Files.createDirectories(temp.resolve("plain"));

            var noHead = git.worktreeSha(unborn);
            var noRepo = git.worktreeSha(plain);

            assertEquals(GitOutcome.UNAVAILABLE, noHead.outcome());
            assertTrue(noHead.detail().startsWith("head"));
            assertEquals(GitOutcome.UNAVAILABLE, noRepo.outcome());
            assertTrue(noRepo.detail().startsWith("open"));
        }
    }

    @Nested
    @DisplayName("linked worktrees")
    class Worktrees {

        private Path worktrees() {
            return repo.resolve(".marshall/local/worktrees");
        }

        private Worktree add(String name, String branch) {
            var result = git.worktreeAdd(new WorktreeAddInput(repo, worktrees().resolve(name), Optional.of(branch), true,
                    "HEAD"));
            assertEquals(GitOutcome.OK, result.outcome(), result.detail());
            return result.value().orElseThrow();
        }

        @Test
        @DisplayName("adds a worktree on a new branch, checked out and usable")
        void addsOnNewBranch() throws Exception {
            Worktree added = add("plan-1", "pm/plan-1");

            assertEquals("hello\n", Files.readString(added.path().resolve("README.md")));
            var info = git.open(added.path()).value().orElseThrow();
            assertTrue(info.linked());
            assertEquals(Optional.of("pm/plan-1"), info.branch());
            assertEquals(git.open(repo).value().orElseThrow().commonDir(), info.commonDir());
            assertTrue(git.worktreeSha(added.path()).isOk());
            assertFalse(Files.exists(repo.resolve(".git/worktrees/plan-1/locked")), "initialization lock removed");

            Files.writeString(added.path().resolve("feature.txt"), "f");
            CommitInfo onBranch = commitIn(added.path(), "feature");

            assertEquals(onBranch.id(), git.log(repo, "pm/plan-1", 1).value().orElseThrow().getFirst().id());
            assertEquals(Optional.of("main"), git.open(repo).value().orElseThrow().branch(),
                    "the main worktree's HEAD is untouched");
        }

        @Test
        @DisplayName("lists the main worktree first, then the linked ones")
        void lists() throws Exception {
            add("plan-1", "pm/plan-1");
            var detached = git.worktreeAdd(new WorktreeAddInput(repo, worktrees().resolve("baseline-x"), Optional.empty(),
                    false, "HEAD"));
            assertTrue(detached.isOk());

            List<Worktree> list = git.worktreeList(repo).value().orElseThrow();

            assertEquals(3, list.size());
            assertTrue(list.getFirst().main());
            assertEquals(repo.toRealPath(), list.getFirst().path());
            assertEquals(Optional.of("main"), list.getFirst().branch());
            assertEquals(Optional.of("baseline-x"), list.get(1).name());
            assertTrue(list.get(1).branch().isEmpty());
            assertTrue(list.get(1).head().isPresent());
            assertEquals(Optional.of("pm/plan-1"), list.get(2).branch());
            assertEquals(list, git.worktreeList(list.get(2).path()).value().orElseThrow(),
                    "listing from a linked worktree yields the same list");
        }

        @Test
        @DisplayName("writes git's own administration layout (checked with the git CLI when present)")
        void interoperatesWithGitCli() throws Exception {
            Worktree added = add("plan-2", "pm/plan-2");
            Path gitCli = Path.of("/usr/bin/git");
            Assumptions.assumeTrue(Files.isExecutable(gitCli), "git CLI not installed");

            var process = new ProcessBuilder(gitCli.toString(), "worktree", "list", "--porcelain").directory(repo.toFile())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertEquals(0, process.waitFor(), output);
            assertTrue(output.contains("worktree " + added.path()), output);
            assertTrue(output.contains("branch refs/heads/pm/plan-2"), output);
            assertFalse(output.contains("prunable"), output);
        }

        @Test
        @DisplayName("refuses targets outside the root, existing targets, branches and unknown start points")
        void refusesAdd() throws Exception {
            add("plan-1", "pm/plan-1");
            write(".marshall/local/worktrees/occupied/file", "x");

            assertEquals(GitOutcome.PATH_REFUSED, git.worktreeAdd(new WorktreeAddInput(repo, temp.resolve("outside"),
                    Optional.empty(), false, "HEAD")).outcome());
            assertEquals(GitOutcome.ALREADY_EXISTS, git.worktreeAdd(new WorktreeAddInput(repo,
                    worktrees().resolve("occupied"), Optional.empty(), false, "HEAD")).outcome());
            assertEquals(GitOutcome.ALREADY_EXISTS, git.worktreeAdd(new WorktreeAddInput(repo,
                    worktrees().resolve("again"), Optional.of("pm/plan-1"), true, "HEAD")).outcome());
            assertEquals(GitOutcome.BRANCH_CHECKED_OUT, git.worktreeAdd(new WorktreeAddInput(repo,
                    worktrees().resolve("again"), Optional.of("pm/plan-1"), false, "HEAD")).outcome());
            assertEquals(GitOutcome.BRANCH_CHECKED_OUT, git.worktreeAdd(new WorktreeAddInput(repo,
                    worktrees().resolve("again"), Optional.of("main"), false, "HEAD")).outcome());
            assertEquals(GitOutcome.UNKNOWN_REVISION, git.worktreeAdd(new WorktreeAddInput(repo,
                    worktrees().resolve("again"), Optional.of("missing"), false, "HEAD")).outcome());
            assertEquals(GitOutcome.UNKNOWN_REVISION, git.worktreeAdd(new WorktreeAddInput(repo,
                    worktrees().resolve("again"), Optional.empty(), false, "no-such-rev")).outcome());
            assertEquals(GitOutcome.NOT_A_REPOSITORY, git.worktreeAdd(new WorktreeAddInput(
                    Files.createDirectories(temp.resolve("plain")), temp.resolve("plain/wt"), Optional.empty(), false,
                    "HEAD")).outcome());
        }

        @Test
        @DisplayName("checks out an existing branch that no worktree holds")
        void existingBranch() {
            Worktree first = add("plan-1", "pm/plan-1");
            assertTrue(git.worktreeRemove(repo, first.path()).isOk());

            var again = git.worktreeAdd(new WorktreeAddInput(repo, worktrees().resolve("plan-1b"), Optional.of("pm/plan-1"),
                    false, "ignored"));

            assertEquals(GitOutcome.OK, again.outcome(), again.detail());
        }

        @Test
        @DisplayName("removes a clean worktree and refuses dirty, locked, main and unknown ones")
        void removes() throws Exception {
            Worktree clean = add("clean", "pm/clean");
            Worktree dirty = add("dirty", "pm/dirty");
            Worktree locked = add("locked", "pm/locked");
            Files.writeString(dirty.path().resolve("untracked.txt"), "x");
            Files.writeString(repo.resolve(".git/worktrees/locked/locked"), "operator lock\n");

            assertEquals(GitOutcome.WORKTREE_DIRTY, git.worktreeRemove(repo, dirty.path()).outcome());
            assertEquals(GitOutcome.WORKTREE_LOCKED, git.worktreeRemove(repo, locked.path()).outcome());
            assertEquals(GitOutcome.PATH_REFUSED, git.worktreeRemove(repo, repo).outcome());
            assertEquals(GitOutcome.WORKTREE_NOT_FOUND, git.worktreeRemove(repo, temp.resolve("nowhere")).outcome());
            assertEquals(GitOutcome.NOT_A_REPOSITORY,
                    git.worktreeRemove(Files.createDirectories(temp.resolve("plain")), clean.path()).outcome());

            var removed = git.worktreeRemove(repo, clean.path());

            assertEquals(GitOutcome.OK, removed.outcome(), removed.detail());
            assertFalse(Files.exists(clean.path()));
            assertFalse(Files.exists(repo.resolve(".git/worktrees/clean")));
            assertEquals(3, git.worktreeList(repo).value().orElseThrow().size());
        }

        @Test
        @DisplayName("reports a worktree whose directory is gone as prunable and removes its administration")
        void prunable() throws Exception {
            Worktree gone = add("gone", "pm/gone");
            LinkedWorktrees.deleteTree(gone.path());

            Worktree listed = git.worktreeList(repo).value().orElseThrow().get(1);
            var removed = git.worktreeRemove(repo, gone.path());

            assertTrue(listed.prunable());
            assertEquals(GitOutcome.OK, removed.outcome(), removed.detail());
            assertFalse(Files.exists(repo.resolve(".git/worktrees/gone")));
        }
    }

    @Nested
    @DisplayName("fetch and push over file://")
    class Transport {

        private Path remote;

        @BeforeEach
        void bareRemote() throws GitAPIException, IOException, URISyntaxException {
            remote = temp.resolve("remote.git");
            Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").setFs(new RefusingFS()).call()
                    .close();
            try (Git jgit = Git.open(repo.toFile())) {
                jgit.remoteAdd().setName("origin").setUri(new URIish(remote.toUri().toString())).call();
            }
        }

        private GitResult<GitOperations.RefUpdate> push(Path root, String expected) {
            return git.push(new PushInput(root, "origin", "HEAD", "refs/heads/main", Optional.ofNullable(expected),
                    Optional.empty()));
        }

        @Test
        @DisplayName("pushes, fetches into a second clone, and rejects a non-fast-forward and a stale lease")
        void pushFetchReject() throws Exception {
            String head = git.open(repo).value().orElseThrow().head().orElseThrow();
            var pushed = push(repo, null);
            assertEquals(GitOutcome.OK, pushed.outcome(), pushed.detail());
            assertEquals(Optional.of(head), pushed.value().orElseThrow().newId());

            Path other = temp.resolve("other");
            Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(other.toFile()).setFs(new RefusingFS())
                    .call().close();
            Files.writeString(other.resolve("other.txt"), "o");
            CommitInfo otherCommit = commitIn(other, "other");
            assertEquals(GitOutcome.OK, push(other, head).outcome(), "lease on the expected head holds");

            write("mine.txt", "m");
            commit("mine");
            assertEquals(GitOutcome.REJECTED_NON_FAST_FORWARD, push(repo, null).outcome());
            assertEquals(GitOutcome.REJECTED_LEASE, push(repo, head).outcome());

            var fetched = git.fetch(new RemoteInput(repo, "origin", List.of(), Optional.empty()));
            assertEquals(GitOutcome.OK, fetched.outcome(), fetched.detail());
            assertEquals(Optional.of(otherCommit.id()), fetched.value().orElseThrow().stream()
                    .filter(u -> "refs/remotes/origin/main".equals(u.ref())).findFirst().orElseThrow().newId());
        }

        @Test
        @DisplayName("refuses remotes outside the policy before sending anything, and unknown remotes")
        void refusesRemotes() throws Exception {
            var httpsOnly = new JGitOperations(RemotePolicy.httpsOrigin(URI.create("https://github.com")));
            var credential = Optional.of(new GitCredential("x-access-token", "secret".toCharArray()));

            assertEquals(GitOutcome.REMOTE_ORIGIN_MISMATCH,
                    httpsOnly.fetch(new RemoteInput(repo, "origin", List.of(), credential)).outcome());
            assertEquals(GitOutcome.REMOTE_ORIGIN_MISMATCH, httpsOnly.push(new PushInput(repo, "origin", "HEAD",
                    "refs/heads/main", Optional.empty(), credential)).outcome());
            assertEquals(GitOutcome.UNKNOWN_REVISION,
                    git.fetch(new RemoteInput(repo, "upstream", List.of(), Optional.empty())).outcome());
            assertEquals(GitOutcome.UNKNOWN_REVISION, git.push(new PushInput(repo, "origin", "no-such-ref",
                    "refs/heads/main", Optional.empty(), Optional.empty())).outcome());
            assertFalse(credential.orElseThrow().toString().contains("secret"));
        }

        @Test
        @DisplayName("compares credentials by the content of the secret")
        void credentialEquality() {
            var credential = new GitCredential("x-access-token", "secret".toCharArray());
            var same = new GitCredential("x-access-token", "secret".toCharArray());

            assertEquals(credential, same);
            assertEquals(credential.hashCode(), same.hashCode());
            assertNotEquals(credential, new GitCredential("x-access-token", "other".toCharArray()));
            assertNotEquals(credential, new GitCredential("user", "secret".toCharArray()));
            assertNotEquals(credential, (Object) "secret");
        }
    }

    @Nested
    @DisplayName("refusing file system")
    class Refusals {

        @Test
        @DisplayName("refuses a commit when a repository hook is present, writing nothing")
        void hookPresent() throws Exception {
            Path hook = repo.resolve(".git/hooks/pre-commit");
            Files.createDirectories(hook.getParent());
            Files.writeString(hook, "#!/bin/sh\ntouch hook-ran\n");
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
            String before = git.open(repo).value().orElseThrow().head().orElseThrow();
            write("x.txt", "x");

            var result = git.commit(new CommitInput(repo, "with hook", AUTHOR, AGENT));

            assertEquals(GitOutcome.CAPABILITY_MISSING, result.outcome());
            assertEquals("repository_hook", result.detail());
            assertEquals(before, git.open(repo).value().orElseThrow().head().orElseThrow());
            assertFalse(Files.exists(repo.resolve("hook-ran")));
        }

        @Test
        @DisplayName("refuses filter drivers instead of running them")
        void filterDriver() throws Exception {
            write(".gitattributes", "*.txt filter=upper\n");
            try (Git jgit = Git.open(repo.toFile())) {
                StoredConfig config = jgit.getRepository().getConfig();
                config.setString("filter", "upper", "clean", "tr a-z A-Z");
                config.save();
            }
            write("data.txt", "lower");

            var result = git.commit(new CommitInput(repo, "filtered", AUTHOR, AGENT));

            assertEquals(GitOutcome.CAPABILITY_MISSING, result.outcome());
            assertEquals("process_execution", result.detail());
        }
    }
}
