/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.gitlab;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import de.cuioss.pm.provider.ci.CiHttpClient;
import de.cuioss.pm.provider.ci.CiResponse;
import de.cuioss.pm.provider.ci.CiResult;
import de.cuioss.pm.provider.ci.Json;
import de.cuioss.pm.provider.ci.PagedResult;

/**
 * A thin GitLab REST client ({@code /api/v4}) for the merge path and the review operations of the
 * native variant: merge and merge-train add bound to the expected head {@code sha}, merge-request
 * discussions (list, reply, resolve), job traces, and the token identity read behind
 * {@code ci.whoami}.
 *
 * @since 0.1
 */
public final class GitLabClient {

    /** Page cap of paginated reads (100 entries per page). */
    private static final String MERGE_REQUESTS = "/merge_requests/";
    static final int MAX_PAGES = 50;

    private final CiHttpClient http;
    private final String project;

    /**
     * A note of a discussion.
     *
     * @param id         the note id
     * @param author     the author's username
     * @param body       the body
     * @param resolvable whether the note can be resolved
     * @param resolved   whether it is resolved
     */
    public record Note(String id, String author, String body, boolean resolvable, boolean resolved) {
    }

    /**
     * A merge-request discussion.
     *
     * @param id    the discussion id
     * @param notes the notes in order
     */
    public record Discussion(String id, List<Note> notes) {

        /**
         * @return whether every resolvable note is resolved
         */
        public boolean resolved() {
            return notes.stream().filter(Note::resolvable).allMatch(Note::resolved);
        }
    }

    /**
     * The identity behind the token.
     *
     * @param id        the token id
     * @param name      the token name
     * @param userId    the user the token acts as
     * @param scopes    the scopes
     * @param active    whether the token is active
     * @param expiresAt the expiry date, empty when none
     */
    public record TokenIdentity(String id, String name, String userId, List<String> scopes, boolean active,
                                Optional<String> expiresAt) {
    }

    /**
     * @param http    the HTTP base on the {@code /api/v4/} endpoint
     * @param project the project id or full path ({@code group/project})
     */
    public GitLabClient(CiHttpClient http, String project) {
        this.http = Objects.requireNonNull(http, "http");
        this.project = "projects/" + URLEncoder.encode(project, StandardCharsets.UTF_8);
    }

    /**
     * Merges a merge request if its head is still {@code sha}.
     *
     * @param iid the merge request iid
     * @param sha the expected head
     * @return the merge commit sha; {@code REJECTED} when not mergeable or the head moved
     */
    public CiResult<String> merge(long iid, String sha) {
        CiResponse response = http.send("PUT", project + MERGE_REQUESTS + iid + "/merge",
                Optional.of(Json.write(Map.of("sha", sha))), Optional.empty());
        if (!response.isOk()) {
            return CiResult.failed(response);
        }
        return Json.string(Json.parseOrNull(response.body()), "merge_commit_sha").map(CiResult::ok)
                .orElseGet(() -> CiResult.of(CiResult.Outcome.FAILED, "merge response without merge_commit_sha"));
    }

    /**
     * Adds a merge request to the merge train if its head is still {@code sha}.
     *
     * @param iid the merge request iid
     * @param sha the expected head
     * @return the response body (the merge train cars)
     */
    public CiResult<String> mergeTrainAdd(long iid, String sha) {
        CiResponse response = http.send("POST", project + "/merge_trains/merge_requests/" + iid,
                Optional.of(Json.write(Map.of("sha", sha))), Optional.empty());
        return response.isOk() ? CiResult.ok(response.body()) : CiResult.failed(response);
    }

    /**
     * Lists the discussions of a merge request.
     *
     * @param iid the merge request iid
     * @return the discussions; {@code complete} is {@code false} at the page cap
     */
    public CiResult<List<Discussion>> discussions(long iid) {
        PagedResult pages = http.getAll(project + MERGE_REQUESTS + iid + "/discussions?per_page=100", MAX_PAGES);
        if (pages.pages().isEmpty()) {
            return CiResult.failed(pages.last());
        }
        var discussions = new ArrayList<Discussion>();
        for (String page : pages.pages()) {
            for (Object discussion : Json.list(Json.parseOrNull(page))) {
                discussions.add(discussion(discussion));
            }
        }
        return CiResult.ok(List.copyOf(discussions), pages.complete());
    }

    /**
     * Replies to a discussion.
     *
     * @param iid          the merge request iid
     * @param discussionId the discussion id
     * @param body         the reply body
     * @return the note id
     */
    public CiResult<String> reply(long iid, String discussionId, String body) {
        CiResponse response = http.send("POST", discussionPath(iid, discussionId) + "/notes",
                Optional.of(Json.write(Map.of("body", body))), Optional.empty());
        if (!response.isOk()) {
            return CiResult.failed(response);
        }
        return Json.string(Json.parseOrNull(response.body()), "id").map(CiResult::ok)
                .orElseGet(() -> CiResult.of(CiResult.Outcome.FAILED, "note response without id"));
    }

    /**
     * Resolves a discussion.
     *
     * @param iid          the merge request iid
     * @param discussionId the discussion id
     * @return whether the discussion is resolved afterwards
     */
    public CiResult<Boolean> resolve(long iid, String discussionId) {
        CiResponse response = http.send("PUT", discussionPath(iid, discussionId) + "?resolved=true", Optional.empty(),
                Optional.empty());
        if (!response.isOk()) {
            return CiResult.failed(response);
        }
        return CiResult.ok(discussion(Json.parseOrNull(response.body())).resolved());
    }

    /**
     * Reads the log of a job.
     *
     * @param jobId the job id
     * @return the trace text
     */
    public CiResult<String> jobTrace(long jobId) {
        CiResponse response = http.get(project + "/jobs/" + jobId + "/trace");
        return response.isOk() ? CiResult.ok(response.body()) : CiResult.failed(response);
    }

    /**
     * Reads the identity of the token ({@code GET /personal_access_tokens/self}).
     *
     * @return the identity
     */
    public CiResult<TokenIdentity> tokenIdentity() {
        CiResponse response = http.get("personal_access_tokens/self");
        if (!response.isOk()) {
            return CiResult.failed(response);
        }
        Object json = Json.parseOrNull(response.body());
        var scopes = Json.list(json, "scopes").stream().map(String::valueOf).toList();
        return CiResult.ok(new TokenIdentity(Json.string(json, "id").orElse(""), Json.string(json, "name").orElse(""),
                Json.string(json, "user_id").orElse(""), scopes, Json.bool(json, "active"),
                Json.string(json, "expires_at")));
    }

    private String discussionPath(long iid, String discussionId) {
        return project + MERGE_REQUESTS + iid + "/discussions/" + URLEncoder.encode(discussionId, StandardCharsets.UTF_8);
    }

    private static Discussion discussion(Object json) {
        var notes = new ArrayList<Note>();
        for (Object note : Json.list(json, "notes")) {
            notes.add(new Note(Json.string(note, "id").orElse(""), Json.string(note, "author", "username").orElse(""),
                    Json.string(note, "body").orElse(""), Json.bool(note, "resolvable"), Json.bool(note, "resolved")));
        }
        return new Discussion(Json.string(json, "id").orElse(""), List.copyOf(notes));
    }
}
