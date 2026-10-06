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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import de.cuioss.pm.provider.ci.Json;

/**
 * The argument vectors of the {@code glab} CLI variant, composed server-side from a closed set of
 * operations.
 * <p>
 * Every operation goes through {@code glab api}, which answers with the REST JSON. A body (a
 * merge-request description, a reply) is never an argument and never a file: it is the JSON request
 * payload streamed on standard input ({@code --input -}). Identifiers that enter the vector are
 * validated, so no value can start with {@code -} and be read as a flag.
 *
 * @since 0.1
 */
public final class GlabCommands {

    private static final String MERGE_REQUESTS = "/merge_requests/";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]*(:[0-9]{1,5})?");
    private static final Pattern DISCUSSION_ID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/][A-Za-z0-9._/-]{0,254}");

    private final String hostname;
    private final String project;

    /**
     * A {@code glab} invocation.
     *
     * @param argv  the argument vector, starting with {@code glab}
     * @param stdin the payload written to standard input, if any
     */
    public record Invocation(List<String> argv, Optional<String> stdin) {

        /**
         * @param argv  the argument vector
         * @param stdin the standard input payload
         */
        public Invocation {
            argv = List.copyOf(argv);
            Objects.requireNonNull(stdin, "stdin");
        }
    }

    /**
     * @param hostname the GitLab host ({@code gitlab.com} or the self-managed host)
     * @param project  the project id or full path
     * @throws IllegalArgumentException if the hostname is not a host name
     */
    public GlabCommands(String hostname, String project) {
        this.hostname = requireMatch(HOST, hostname, "hostname");
        this.project = "projects/" + URLEncoder.encode(Objects.requireNonNull(project, "project"), StandardCharsets.UTF_8);
    }

    /**
     * @param iid the merge request iid
     * @param sha the expected head
     * @return the merge invocation
     */
    public Invocation merge(long iid, String sha) {
        return withBody("PUT", project + MERGE_REQUESTS + positive(iid) + "/merge", Map.of("sha", sha));
    }

    /**
     * @param iid the merge request iid
     * @param sha the expected head
     * @return the merge-train add invocation
     */
    public Invocation mergeTrainAdd(long iid, String sha) {
        return withBody("POST", project + "/merge_trains/merge_requests/" + positive(iid), Map.of("sha", sha));
    }

    /**
     * @param iid the merge request iid
     * @return the paginated discussion list invocation
     */
    public Invocation discussions(long iid) {
        return read(List.of("--paginate", project + MERGE_REQUESTS + positive(iid) + "/discussions?per_page=100"));
    }

    /**
     * @param iid          the merge request iid
     * @param discussionId the discussion id
     * @param body         the reply, streamed on standard input
     * @return the reply invocation
     */
    public Invocation reply(long iid, String discussionId, String body) {
        return withBody("POST", discussionPath(iid, discussionId) + "/notes", Map.of("body", body));
    }

    /**
     * @param iid          the merge request iid
     * @param discussionId the discussion id
     * @return the resolve invocation
     */
    public Invocation resolve(long iid, String discussionId) {
        return read(List.of("--method", "PUT", discussionPath(iid, discussionId) + "?resolved=true"));
    }

    /**
     * @param jobId the job id
     * @return the job trace invocation
     */
    public Invocation jobTrace(long jobId) {
        return read(List.of(project + "/jobs/" + positive(jobId) + "/trace"));
    }

    /**
     * @return the token identity invocation
     */
    public Invocation tokenIdentity() {
        return read(List.of("personal_access_tokens/self"));
    }

    /**
     * @param sourceBranch the source branch
     * @param targetBranch the target branch
     * @param title        the title
     * @param description  the description, streamed on standard input
     * @return the merge request creation invocation
     */
    public Invocation createMergeRequest(String sourceBranch, String targetBranch, String title, String description) {
        var body = new LinkedHashMap<String, Object>();
        body.put("source_branch", requireMatch(BRANCH, sourceBranch, "sourceBranch"));
        body.put("target_branch", requireMatch(BRANCH, targetBranch, "targetBranch"));
        body.put("title", title);
        body.put("description", description);
        return withBody("POST", project + "/merge_requests", body);
    }

    private Invocation withBody(String method, String endpoint, Map<String, ?> body) {
        var argv = base();
        argv.addAll(List.of("--method", method, "--header", "Content-Type: application/json", "--input", "-", endpoint));
        return new Invocation(argv, Optional.of(Json.write(body)));
    }

    private Invocation read(List<String> arguments) {
        var argv = base();
        argv.addAll(arguments);
        return new Invocation(argv, Optional.empty());
    }

    private List<String> base() {
        return new ArrayList<>(List.of("glab", "api", "--hostname", hostname));
    }

    private String discussionPath(long iid, String discussionId) {
        return project + MERGE_REQUESTS + positive(iid) + "/discussions/"
                + requireMatch(DISCUSSION_ID, discussionId, "discussionId");
    }

    private static long positive(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive: " + id);
        }
        return id;
    }

    private static String requireMatch(Pattern pattern, String value, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return value;
    }
}
