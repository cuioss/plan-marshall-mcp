/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.verify.git;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import de.cuioss.pm.provider.git.GitOperations.CommitInput;
import de.cuioss.pm.provider.git.GitOperations.Identity;
import de.cuioss.pm.provider.git.GitOperations.PushInput;
import de.cuioss.pm.provider.git.GitOperations.RemoteInput;
import de.cuioss.pm.provider.git.GitOperations.WorktreeAddInput;
import de.cuioss.pm.provider.git.GitResult;
import de.cuioss.pm.provider.git.JGitOperations;
import de.cuioss.pm.provider.git.RefusingFS;
import de.cuioss.pm.provider.git.RemotePolicy;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;

/**
 * {@code POST /api/v1/spike/git} {@code {"op":"…","repo":"…"}}: runs JGit operations of the native
 * git variant inside the daemon and answers each step's outcome.
 * <ul>
 *   <li>{@code scenario}: in the empty or absent directory {@code repo}: init, commit, worktree-sha,
 *       log, linked worktree add/list/commit/remove, push to and fetch from a bare {@code file://}
 *       remote beside it, and the refused commit with a repository hook present;</li>
 *   <li>{@code open}, {@code worktree-sha}, {@code log}, {@code worktree-list}: read operations on an
 *       existing repository.</li>
 * </ul>
 */
@jakarta.ws.rs.Path("/api/v1/spike/git")
@Authenticated
public class GitSpikeResource {

    private static final String WORKTREE_SHA = "worktree-sha";
    private static final String WORKTREE_LIST = "worktree-list";
    private static final String ORIGIN = "origin";
    private static final Identity AUTHOR = new Identity("PM-MCP Spike", "spike@example.invalid");

    /**
     * The request.
     *
     * @param op   the operation
     * @param repo the repository directory
     */
    public record Request(String op, String repo) {
    }

    /**
     * @param request the request
     * @return {@code 200} with the steps and their outcomes, {@code 400} for an unknown operation
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response run(Request request) {
        if (request == null || request.op() == null || request.repo() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "op and repo required")).build();
        }
        Path repo = Path.of(request.repo()).toAbsolutePath();
        var git = new JGitOperations(RemotePolicy.localOnly());
        var steps = new LinkedHashMap<String, Object>();
        long start = System.nanoTime();
        switch (request.op()) {
            case "scenario" -> scenario(git, repo, steps);
            case "open" -> step(steps, "open", git.open(repo));
            case WORKTREE_SHA -> step(steps, WORKTREE_SHA, git.worktreeSha(repo));
            case "log" -> step(steps, "log", git.log(repo, "HEAD", 10));
            case WORKTREE_LIST -> step(steps, WORKTREE_LIST, git.worktreeList(repo));
            default -> {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "unknown op")).build();
            }
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("op", request.op());
        body.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
        body.put("steps", steps);
        return Response.ok(body).build();
    }

    private static void scenario(JGitOperations git, Path repo, Map<String, Object> steps) {
        try {
            step(steps, "init", git.init(repo, "main"));
            Files.writeString(repo.resolve("README.md"), "spike\n", StandardCharsets.UTF_8);
            step(steps, "commit", git.commit(new CommitInput(repo, "spike: initial\n\nmulti-line body\n", AUTHOR,
                    AUTHOR)));
            step(steps, WORKTREE_SHA, git.worktreeSha(repo));
            step(steps, "log", git.log(repo, "HEAD", 5));
            Path worktree = repo.resolve(".marshall/local/worktrees/spike-plan");
            step(steps, "worktree-add", git.worktreeAdd(new WorktreeAddInput(repo, worktree, Optional.of("pm/spike"),
                    true, "HEAD")));
            Files.writeString(worktree.resolve("feature.txt"), "feature\n", StandardCharsets.UTF_8);
            step(steps, "worktree-commit", git.commit(new CommitInput(worktree, "spike: feature", AUTHOR, AUTHOR)));
            step(steps, WORKTREE_LIST, git.worktreeList(repo));
            step(steps, "worktree-remove", git.worktreeRemove(repo, worktree));
            Path remote = repo.resolveSibling(repo.getFileName() + ".remote.git");
            Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").setFs(new RefusingFS()).call()
                    .close();
            try (Git jgit = Git.open(repo.toFile(), new RefusingFS())) {
                jgit.remoteAdd().setName(ORIGIN).setUri(new URIish(remote.toUri().toString())).call();
            }
            step(steps, "push", git.push(new PushInput(repo, ORIGIN, "HEAD", "refs/heads/main", Optional.empty(),
                    Optional.empty())));
            step(steps, "fetch", git.fetch(new RemoteInput(repo, ORIGIN, List.of(), Optional.empty())));
            Path hook = repo.resolve(".git/hooks/pre-commit");
            Files.createDirectories(hook.getParent());
            Files.writeString(hook, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwx------"));
            Files.writeString(repo.resolve("hooked.txt"), "x\n", StandardCharsets.UTF_8);
            step(steps, "commit-with-hook", git.commit(new CommitInput(repo, "refused", AUTHOR, AUTHOR)));
        } catch (IOException | GitAPIException | URISyntaxException e) {
            steps.put("setup_error", e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private static void step(Map<String, Object> steps, String name, GitResult<?> result) {
        var step = new LinkedHashMap<String, Object>();
        step.put("outcome", result.outcome().name());
        step.put("detail", result.detail());
        result.value().ifPresent(v -> step.put("value", String.valueOf(v)));
        steps.put(name, step);
    }
}
