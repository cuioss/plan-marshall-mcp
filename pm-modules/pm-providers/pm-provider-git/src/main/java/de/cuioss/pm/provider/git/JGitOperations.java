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
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefLeaseSpec;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FS;

/**
 * The native variant of the git contract on JGit.
 * <p>
 * Every repository is opened with a {@link RefusingFS}, so no operation starts a process; an
 * operation that would run a hook or a filter driver ends {@link GitOutcome#CAPABILITY_MISSING}.
 * Commits are never signed. Transport operations contact only remotes the {@link RemotePolicy}
 * admits, checked against the effective URL after {@code insteadOf} rewriting, before anything is
 * sent.
 *
 * @since 0.1
 */
public final class JGitOperations implements GitOperations {

    private final FS fs;
    private final RemotePolicy remotePolicy;

    /**
     * @param remotePolicy the remotes the transport may contact
     */
    public JGitOperations(RemotePolicy remotePolicy) {
        this.fs = new RefusingFS();
        this.remotePolicy = Objects.requireNonNull(remotePolicy, "remotePolicy");
    }

    /**
     * Initializes an empty repository with the refusing file system (setup and tests).
     *
     * @param root          the worktree root to create
     * @param initialBranch the initial branch name
     * @return {@code OK} with the repository facts, or {@code FAILED}
     */
    public GitResult<RepositoryInfo> init(Path root, String initialBranch) {
        try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch(initialBranch).setFs(fs).call()) {
            return GitResult.ok(info(git.getRepository()));
        } catch (GitAPIException | IOException | JGitInternalException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<RepositoryInfo> open(Path root) {
        try (Repository repository = openRepository(root)) {
            return GitResult.ok(info(repository));
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, root.toString());
        } catch (IOException | IllegalArgumentException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<WorktreeSha> worktreeSha(Path root) {
        String step = "open";
        try (Repository repository = openRepository(root)) {
            step = "head";
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null) {
                return GitResult.of(GitOutcome.UNAVAILABLE, "head: unresolvable HEAD");
            }
            step = "status";
            Status status = Git.wrap(repository).status().call();
            var paths = new TreeSet<String>();
            paths.addAll(status.getAdded());
            paths.addAll(status.getChanged());
            paths.addAll(status.getModified());
            paths.addAll(status.getRemoved());
            paths.addAll(status.getMissing());
            paths.addAll(status.getUntracked());
            paths.addAll(status.getConflicting());
            step = "read";
            String digest = WorktreeShaDigest.digest(head.name(), repository.getWorkTree().toPath(), paths);
            return GitResult.ok(new WorktreeSha(digest, WorktreeSha.CURRENT_VERSION));
        } catch (GitAPIException | IOException | JGitInternalException | IllegalArgumentException e) {
            return GitResult.of(GitOutcome.UNAVAILABLE, step + ": " + e.getClass().getName());
        }
    }

    @Override
    public GitResult<Worktree> worktreeAdd(WorktreeAddInput input) {
        Path target;
        try {
            Path root = input.root().toRealPath();
            target = canonical(input.path());
            if (!target.startsWith(root) || target.equals(root)) {
                return GitResult.of(GitOutcome.PATH_REFUSED, input.path() + " is not inside " + root);
            }
            if (Files.exists(target) && !isEmptyDirectory(target)) {
                return GitResult.of(GitOutcome.ALREADY_EXISTS, target.toString());
            }
        } catch (IOException e) {
            return failure(e);
        }
        try (Repository main = openRepository(input.root())) {
            return addWorktree(main, input, target);
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.root().toString());
        } catch (RefusingFS.CapabilityRefusedException e) {
            return GitResult.of(GitOutcome.CAPABILITY_MISSING, e.getReason());
        } catch (IOException | JGitInternalException | IllegalArgumentException e) {
            return failure(e);
        }
    }

    private GitResult<Worktree> addWorktree(Repository main, WorktreeAddInput input, Path target) throws IOException {
        Path commonDir = main.getCommonDirectory().toPath().toRealPath();
        Optional<String> branchRef = input.branch().map(b -> Constants.R_HEADS + b);
        var resolved = branchRef.isPresent() && !input.createBranch()
                ? existingBranchStart(main, commonDir, input.branch().get(), branchRef.get())
                : newStart(main, input, branchRef);
        if (resolved.refusal() != null) {
            return resolved.refusal();
        }
        ObjectId start = resolved.id();
        Files.createDirectories(target);
        Path admin = LinkedWorktrees.create(commonDir, target, branchRef.orElse(start.name()));
        try (Repository linked = openRepository(target); RevWalk walk = new RevWalk(linked)) {
            RevCommit commit = walk.parseCommit(start);
            var checkout = new DirCacheCheckout(linked, linked.lockDirCache(), commit.getTree());
            checkout.setFailOnConflict(true);
            checkout.checkout();
            LinkedWorktrees.unlock(admin);
            return GitResult.ok(new Worktree(target, Optional.of(admin.getFileName().toString()), input.branch(),
                    Optional.of(start.name()), false, false, false));
        } catch (IOException | JGitInternalException e) {
            LinkedWorktrees.delete(commonDir, admin.getFileName().toString(), target);
            throw e;
        }
    }

    /** The commit a new worktree starts at, or the refusal that ends the operation. */
    private record StartPoint(ObjectId id, GitResult<Worktree> refusal) {

        static StartPoint of(ObjectId id) {
            return new StartPoint(id, null);
        }

        static StartPoint refused(GitOutcome outcome, String detail) {
            return new StartPoint(null, GitResult.of(outcome, detail));
        }
    }

    private static StartPoint existingBranchStart(Repository main, Path commonDir, String branch, String branchRef)
            throws IOException {
        Ref existing = main.exactRef(branchRef);
        if (existing == null) {
            return StartPoint.refused(GitOutcome.UNKNOWN_REVISION, branchRef);
        }
        if (checkedOut(main, commonDir, branch)) {
            return StartPoint.refused(GitOutcome.BRANCH_CHECKED_OUT, branch);
        }
        return StartPoint.of(existing.getObjectId());
    }

    private static StartPoint newStart(Repository main, WorktreeAddInput input, Optional<String> branchRef)
            throws IOException {
        ObjectId start = main.resolve(input.startPoint() + "^{commit}");
        if (start == null) {
            return StartPoint.refused(GitOutcome.UNKNOWN_REVISION, input.startPoint());
        }
        if (branchRef.isPresent()) {
            if (main.exactRef(branchRef.get()) != null) {
                return StartPoint.refused(GitOutcome.ALREADY_EXISTS, branchRef.get());
            }
            var update = main.updateRef(branchRef.get());
            update.setNewObjectId(start);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.setRefLogMessage("branch: Created from " + input.startPoint(), false);
            Result result = update.update();
            if (result != Result.NEW) {
                return StartPoint.refused(GitOutcome.FAILED, "branch creation: " + result);
            }
        }
        return StartPoint.of(start);
    }

    private static boolean checkedOut(Repository main, Path commonDir, String branch) throws IOException {
        if (!main.isBare() && branch.equals(main.getBranch())) {
            return true;
        }
        return LinkedWorktrees.list(commonDir).stream().anyMatch(w -> w.branch().filter(branch::equals).isPresent());
    }

    @Override
    public GitResult<List<Worktree>> worktreeList(Path root) {
        try (Repository repository = openRepository(root)) {
            Path commonDir = repository.getCommonDirectory().toPath().toRealPath();
            var worktrees = new ArrayList<Worktree>();
            try (Repository main = mainRepository(commonDir)) {
                Optional<ObjectId> head = Optional.ofNullable(main.resolve(Constants.HEAD));
                Ref headRef = main.exactRef(Constants.HEAD);
                Optional<String> branch = headRef != null && headRef.isSymbolic()
                        ? Optional.of(Repository.shortenRefName(headRef.getTarget().getName()))
                        : Optional.empty();
                worktrees.add(new Worktree(main.getWorkTree().toPath().toRealPath(), Optional.empty(), branch,
                        head.map(ObjectId::name), true, false, false));
            }
            worktrees.addAll(LinkedWorktrees.list(commonDir));
            return GitResult.ok(List.copyOf(worktrees));
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, root.toString());
        } catch (IOException | IllegalArgumentException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<Path> worktreeRemove(Path root, Path worktree) {
        GitResult<List<Worktree>> listed = worktreeList(root);
        if (!listed.isOk()) {
            return GitResult.of(listed.outcome(), listed.detail());
        }
        try {
            Path target = worktree.toAbsolutePath().normalize();
            Path real = Files.exists(target) ? target.toRealPath() : target;
            Optional<Worktree> match = listed.value().orElseThrow().stream()
                    .filter(w -> w.path().equals(real) || w.path().equals(target)).findFirst();
            if (match.isEmpty()) {
                return GitResult.of(GitOutcome.WORKTREE_NOT_FOUND, target.toString());
            }
            Worktree found = match.get();
            if (found.main()) {
                return GitResult.of(GitOutcome.PATH_REFUSED, "the main worktree cannot be removed");
            }
            if (found.locked()) {
                return GitResult.of(GitOutcome.WORKTREE_LOCKED, found.path().toString());
            }
            Path commonDir;
            try (Repository repository = openRepository(root)) {
                commonDir = repository.getCommonDirectory().toPath().toRealPath();
            }
            if (!found.prunable()) {
                try (Repository linked = openRepository(found.path())) {
                    if (!Git.wrap(linked).status().call().isClean()) {
                        return GitResult.of(GitOutcome.WORKTREE_DIRTY, found.path().toString());
                    }
                }
            }
            LinkedWorktrees.delete(commonDir, found.name().orElseThrow(), found.path());
            return GitResult.ok(found.path());
        } catch (RefusingFS.CapabilityRefusedException e) {
            return GitResult.of(GitOutcome.CAPABILITY_MISSING, e.getReason());
        } catch (GitAPIException | IOException | JGitInternalException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<CommitInfo> commit(CommitInput input) {
        try (Repository repository = openRepository(input.root()); Git git = Git.wrap(repository)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            RevCommit commit = git.commit()
                    .setMessage(input.message())
                    .setAuthor(new PersonIdent(input.author().name(), input.author().email()))
                    .setCommitter(new PersonIdent(input.committer().name(), input.committer().email()))
                    .setSign(Boolean.FALSE)
                    .setAllowEmpty(false)
                    .call();
            return GitResult.ok(toInfo(commit));
        } catch (EmptyCommitException e) {
            return GitResult.of(GitOutcome.NOTHING_TO_COMMIT, "no changes");
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.root().toString());
        } catch (RefusingFS.CapabilityRefusedException e) {
            return GitResult.of(GitOutcome.CAPABILITY_MISSING, e.getReason());
        } catch (GitAPIException | IOException | JGitInternalException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<List<CommitInfo>> log(Path root, String revision, int maxCount) {
        try (Repository repository = openRepository(root); RevWalk walk = new RevWalk(repository)) {
            ObjectId start = repository.resolve(revision + "^{commit}");
            if (start == null) {
                return GitResult.of(GitOutcome.UNKNOWN_REVISION, revision);
            }
            walk.markStart(walk.parseCommit(start));
            var commits = new ArrayList<CommitInfo>();
            for (RevCommit commit : walk) {
                if (commits.size() >= Math.max(1, maxCount)) {
                    break;
                }
                commits.add(toInfo(commit));
            }
            return GitResult.ok(List.copyOf(commits));
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, root.toString());
        } catch (IOException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<List<RefUpdate>> fetch(RemoteInput input) {
        try (Repository repository = openRepository(input.root()); Git git = Git.wrap(repository)) {
            Optional<GitResult<List<RefUpdate>>> refused = checkRemote(repository, input.remote(), false);
            if (refused.isPresent()) {
                return refused.get();
            }
            var command = git.fetch().setRemote(input.remote())
                    .setRefSpecs(input.refSpecs().stream().map(RefSpec::new).toList());
            input.credential().ifPresent(c -> command.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(c.username(), c.secret())));
            FetchResult result = command.call();
            var updates = new ArrayList<RefUpdate>();
            for (TrackingRefUpdate update : result.getTrackingRefUpdates()) {
                updates.add(new RefUpdate(update.getLocalName(), id(update.getOldObjectId()),
                        id(update.getNewObjectId()), update.getResult().name().toLowerCase(Locale.ROOT)));
            }
            return GitResult.ok(List.copyOf(updates));
        } catch (RefusingFS.CapabilityRefusedException e) {
            return GitResult.of(GitOutcome.CAPABILITY_MISSING, e.getReason());
        } catch (InvalidRemoteException e) {
            return GitResult.of(GitOutcome.UNKNOWN_REVISION, input.remote());
        } catch (GitAPIException | IOException | JGitInternalException | URISyntaxException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<RefUpdate> push(PushInput input) {
        try (Repository repository = openRepository(input.root()); Git git = Git.wrap(repository)) {
            Optional<GitResult<RefUpdate>> refused = checkRemote(repository, input.remote(), true);
            if (refused.isPresent()) {
                return refused.get();
            }
            ObjectId local = repository.resolve(input.localRef());
            if (local == null) {
                return GitResult.of(GitOutcome.UNKNOWN_REVISION, input.localRef());
            }
            var command = git.push().setRemote(input.remote())
                    .setRefSpecs(new RefSpec(local.name() + ":" + input.remoteRef()));
            input.expectedRemoteHead().ifPresent(e -> command.setRefLeaseSpecs(new RefLeaseSpec(input.remoteRef(), e)));
            input.credential().ifPresent(c -> command.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(c.username(), c.secret())));
            for (PushResult result : command.call()) {
                RemoteRefUpdate update = result.getRemoteUpdate(input.remoteRef());
                if (update != null) {
                    return toPushResult(update);
                }
            }
            return GitResult.of(GitOutcome.FAILED, "no update reported for " + input.remoteRef());
        } catch (RefusingFS.CapabilityRefusedException e) {
            return GitResult.of(GitOutcome.CAPABILITY_MISSING, e.getReason());
        } catch (InvalidRemoteException e) {
            return GitResult.of(GitOutcome.UNKNOWN_REVISION, input.remote());
        } catch (GitAPIException | IOException | JGitInternalException | URISyntaxException e) {
            return failure(e);
        }
    }

    private static GitResult<RefUpdate> toPushResult(RemoteRefUpdate update) {
        var ref = new RefUpdate(update.getRemoteName(), id(update.getExpectedOldObjectId()), id(update.getNewObjectId()),
                update.getStatus().name().toLowerCase(Locale.ROOT));
        return switch (update.getStatus()) {
            case OK, UP_TO_DATE -> GitResult.ok(ref);
            case REJECTED_NONFASTFORWARD -> GitResult.of(GitOutcome.REJECTED_NON_FAST_FORWARD, update.getRemoteName());
            case REJECTED_REMOTE_CHANGED -> GitResult.of(GitOutcome.REJECTED_LEASE, update.getRemoteName());
            default -> GitResult.of(GitOutcome.FAILED, update.getStatus() + ": " + update.getMessage());
        };
    }

    private <V> Optional<GitResult<V>> checkRemote(Repository repository, String remote, boolean push)
            throws URISyntaxException {
        var config = new RemoteConfig(repository.getConfig(), remote);
        List<URIish> uris = push && !config.getPushURIs().isEmpty() ? config.getPushURIs() : config.getURIs();
        if (uris.isEmpty()) {
            return Optional.of(GitResult.of(GitOutcome.UNKNOWN_REVISION, "remote " + remote));
        }
        for (URIish uri : uris) {
            if (!remotePolicy.permits(uri)) {
                return Optional.of(GitResult.of(GitOutcome.REMOTE_ORIGIN_MISMATCH, uri.toString()));
            }
        }
        return Optional.empty();
    }

    private Repository openRepository(Path root) throws IOException {
        var builder = new FileRepositoryBuilder().setFS(fs).setMustExist(true);
        Path dotGit = root.resolve(Constants.DOT_GIT);
        if (Files.exists(dotGit)) {
            builder.setWorkTree(root.toFile());
        } else {
            builder.findGitDir(root.toFile());
            if (builder.getGitDir() == null) {
                throw new RepositoryNotFoundException(root.toFile());
            }
        }
        return builder.build();
    }

    private Repository mainRepository(Path commonDir) throws IOException {
        return new FileRepositoryBuilder().setFS(fs).setMustExist(true).setGitDir(commonDir.toFile()).build();
    }

    private static RepositoryInfo info(Repository repository) throws IOException {
        Path gitDir = repository.getDirectory().toPath().toRealPath();
        Path commonDir = repository.getCommonDirectory().toPath().toRealPath();
        Ref head = repository.exactRef(Constants.HEAD);
        Optional<String> branch = head != null && head.isSymbolic()
                ? Optional.of(Repository.shortenRefName(head.getTarget().getName()))
                : Optional.empty();
        Optional<String> id = Optional.ofNullable(repository.resolve(Constants.HEAD)).map(ObjectId::name);
        return new RepositoryInfo(repository.getWorkTree().toPath().toRealPath(), gitDir, commonDir, id, branch,
                !gitDir.equals(commonDir));
    }

    private static CommitInfo toInfo(RevCommit commit) {
        var parents = new ArrayList<String>();
        for (RevCommit parent : commit.getParents()) {
            parents.add(parent.name());
        }
        PersonIdent author = commit.getAuthorIdent();
        PersonIdent committer = commit.getCommitterIdent();
        return new CommitInfo(commit.name(), List.copyOf(parents), new Identity(author.getName(), author.getEmailAddress()),
                author.getWhenAsInstant(), new Identity(committer.getName(), committer.getEmailAddress()),
                committer.getWhenAsInstant(), commit.getFullMessage());
    }

    private static Optional<String> id(ObjectId id) {
        return id == null || ObjectId.zeroId().equals(id) ? Optional.empty() : Optional.of(id.name());
    }

    /**
     * Resolves a path that may not exist yet: the nearest existing ancestor is resolved with
     * {@link Path#toRealPath}, the missing segments are appended.
     */
    private static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (!Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing.toRealPath().resolve(existing.relativize(absolute));
    }

    private static boolean isEmptyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    private static <V> GitResult<V> failure(Exception e) {
        return GitResult.of(GitOutcome.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
