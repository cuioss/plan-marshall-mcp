/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.gitlab.spike;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;


import de.cuioss.pm.provider.ci.CiEndpoint;
import de.cuioss.pm.provider.ci.CiHttpClient;
import de.cuioss.pm.provider.ci.CiResponse;
import de.cuioss.pm.provider.ci.CiResult;
import de.cuioss.pm.provider.ci.Json;
import de.cuioss.pm.provider.gitlab.GitLabClient;
import de.cuioss.pm.provider.gitlab.GlabCommands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * M20 and M21 (doc/roadmap/technical_macos.adoc) against the operator's GitLab fixture project: the thin client
 * {@link GitLabClient} (token identity, discussions list, reply and resolve, job trace, merge train add, merge with
 * sha), and every {@code glab} form {@link GlabCommands} builds, run for real with the body on standard input.
 * <p>
 * Opt-in: {@code -Dspike.fixture=~/.config/pm-mcp-fixture/app.json}, with the token of the operator's {@code glab}
 * login in the environment variable {@code PM_FIXTURE_GITLAB_TOKEN} (never on a command line). Figures:
 * {@code target/verification-results/m20-gitlab-client.json} and {@code m21-glab-stdin.json}.
 */
@EnabledIfSystemProperty(named = FixtureGitLabIT.PROPERTY, matches = ".+")
@DisplayName("M20-M21: GitLab thin client and glab forms against the fixture project")
class FixtureGitLabIT {

    static final String PROPERTY = "spike.fixture";
    private static final String TOKEN_ENV = "PM_FIXTURE_GITLAB_TOKEN";
    private static final String HOST = "gitlab.com";
    private static final String OUTCOME = "outcome";
    private static final String DETAIL = "detail";
    private static final String MAIN = "main";
    private static final Set<String> SETTLING = Set.of("checking", "unchecked", "preparing", "approvals_syncing");
    private static final Duration PIPELINE_BOUND = Duration.ofMinutes(6);
    private static final Duration POLL = Duration.ofSeconds(10);

    @TempDir
    Path temp;

    private final List<String> secrets = new ArrayList<>();
    private final Map<String, Object> m20 = new LinkedHashMap<>();
    private final Map<String, Object> m21 = new LinkedHashMap<>();
    private final Map<String, Object> ops = new LinkedHashMap<>();
    private final Map<String, Object> forms = new LinkedHashMap<>();
    private final List<Runnable> cleanup = new ArrayList<>();
    private String project;
    private String projectPath;
    private CiHttpClient http;
    private GitLabClient gitlab;
    private long jobId;

    @Test
    @DisplayName("runs the thin-client operations and the glab forms against the fixture project")
    void shouldVerifyAgainstFixture() throws Exception {
        Path fixture = expand(System.getProperty(PROPERTY));
        Object settings = Json.parse(Files.readString(fixture));
        project = Json.string(settings, "gitlab_project").orElseThrow();
        projectPath = "projects/" + URLEncoder.encode(project, StandardCharsets.UTF_8);
        Optional<String> glabToken = Optional.ofNullable(System.getenv(TOKEN_ENV)).map(String::strip)
                .filter(t -> !t.isEmpty() && t.chars().noneMatch(Character::isWhitespace));
        Optional<String> fixtureToken = Json.string(settings, "gitlab_token").filter(t -> !t.isBlank());
        m20.put("glab_token_in_env", glabToken.isPresent());
        String token = glabToken.or(() -> fixtureToken).orElse(null);
        m20.put("client_token_source", glabToken.isPresent() ? "glab login (" + TOKEN_ENV + ")"
                : fixtureToken.isPresent() ? "fixture file gitlab_token" : "none");
        boolean m20Pass = false;
        boolean m21Pass = false;
        try {
            if (token == null) {
                m20.put("error", "no GitLab token: " + TOKEN_ENV + " unset or not a token, no gitlab_token in fixture");
            } else {
                secrets.add(token);
                fixtureToken.ifPresent(secrets::add);
                http = new CiHttpClient(CiEndpoint.of(URI.create("https://" + HOST + "/api/v4/")),
                        () -> Optional.of(token), Map.of());
                gitlab = new GitLabClient(http, project);
                m20.put("project", project);
                identity(token, fixtureToken.filter(t -> !t.equals(token)));
                clientOperations();
                glabForms();
            }
        } finally {
            cleanup.forEach(Runnable::run);
            m20Pass = evaluateM20();
            m21Pass = evaluateM21();
            write("m20-gitlab-client", m20, m20Pass);
            write("m21-glab-stdin", m21, m21Pass);
            if (http != null) {
                http.close();
            }
        }
        boolean m20Result = m20Pass;
        boolean m21Result = m21Pass;
        assertAll(() -> assertTrue(m20Result, "M20"), () -> assertTrue(m21Result, "M21"));
    }

    // --- M20 -----------------------------------------------------------------------------------------------------

    private void identity(String token, Optional<String> fixtureToken) {
        m20.put("client_token_kind", tokenKind(token));
        CiResponse user = http.get("user");
        m20.put("token_user", Json.string(Json.parseOrNull(user.body()), "username").orElse(user.detail()));
        CiResult<GitLabClient.TokenIdentity> identity = gitlab.tokenIdentity();
        op("token_identity_client_token", identity.outcome().name(), identity.detail()
                + identity.value().map(i -> " name=" + i.name() + " scopes=" + i.scopes()).orElse(""));
        fixtureToken.ifPresent(t -> {
            m20.put("fixture_token_kind", tokenKind(t));
            try (var other = new CiHttpClient(CiEndpoint.of(URI.create("https://" + HOST + "/api/v4/")),
                         () -> Optional.of(t), Map.of())) {
                CiResult<GitLabClient.TokenIdentity> second = new GitLabClient(other, project).tokenIdentity();
                op("token_identity_fixture_token", second.outcome().name(), second.detail()
                        + second.value().map(i -> " name=" + i.name() + " scopes=" + i.scopes() + " active="
                        + i.active()).orElse(""));
            }
        });
    }

    private Map<String, Object> tokenKind(String token) {
        var kind = new LinkedHashMap<String, Object>();
        String prefix = token.contains("-") ? token.substring(0, token.indexOf('-') + 1) : "";
        kind.put("prefix", prefix.length() <= 8 ? prefix : "");
        kind.put("length", token.length());
        try (var root = new CiHttpClient(CiEndpoint.of(URI.create("https://" + HOST + "/")), () -> Optional.of(token),
                     Map.of())) {
            CiResponse info = root.get("oauth/token/info");
            Object json = Json.parseOrNull(info.body());
            kind.put("oauth_token_info_status", info.status());
            if (info.isOk()) {
                kind.put("oauth_scope", Json.at(json, "scope").orElse(null));
                kind.put("oauth_expires_in_seconds", Json.string(json, "expires_in").orElse(""));
                kind.put("oauth_application", Json.at(json, "application").isPresent());
            }
            kind.put("kind", "glpat-".equals(prefix) ? "personal_access_token"
                    : info.isOk() ? "oauth_access_token" : "unknown");
        }
        return kind;
    }

    private void clientOperations() {
        String branch = "pm-fixture/m20-" + System.currentTimeMillis();
        String sha = commitOnNewBranch(branch, "m20");
        if (sha == null) {
            return;
        }
        Object mr = createMergeRequest(branch, "m20");
        if (mr == null) {
            return;
        }
        long iid = Long.parseLong(Json.string(mr, "iid").orElseThrow());
        m20.put("mr_url", Json.string(mr, "web_url").orElse(""));
        cleanup.add(() -> closeIfOpen(iid, branch));
        String discussionId = startDiscussion(iid, "Fixture discussion " + branch);
        CiResult<List<GitLabClient.Discussion>> listed = gitlab.discussions(iid);
        op("discussions_list", listed.outcome().name(), listed.detail()
                + listed.value().map(d -> " discussions=" + d.size() + " complete=" + listed.complete()).orElse(""));
        if (discussionId != null) {
            CiResult<String> reply = gitlab.reply(iid, discussionId, "Fixture reply\n\nwith a second paragraph");
            op("discussion_reply", reply.outcome().name(), reply.detail() + reply.value().map(n -> " note=" + n)
                    .orElse(""));
            CiResult<Boolean> resolved = gitlab.resolve(iid, discussionId);
            op("discussion_resolve", resolved.outcome().name(), resolved.detail()
                    + resolved.value().map(r -> " resolved=" + r).orElse(""));
        }
        pipelineAndTrace(branch);
        waitMergeable(iid, "m20_merge_status");
        CiResult<String> train = gitlab.mergeTrainAdd(iid, sha);
        op("merge_train_add", train.outcome().name(), train.detail() + train.value().map(b -> " body="
                + truncate(b, 500)).orElse(""));
        CiResult<String> merged = gitlab.merge(iid, sha);
        op("merge", merged.outcome().name(), merged.detail() + merged.value().map(s -> " merge_commit_sha=" + s)
                .orElse(""));
        Object after = Json.parseOrNull(http.get(projectPath + "/merge_requests/" + iid).body());
        m20.put("mr_state_after_merge", Json.string(after, "state").orElse(""));
        m20.put("merge_method_commit", Json.string(after, "merge_commit_sha").orElse(""));
    }

    private void pipelineAndTrace(String branch) {
        Instant deadline = Instant.now().plus(PIPELINE_BOUND);
        Object pipeline = null;
        List<Object> jobs = List.of();
        while (Instant.now().isBefore(deadline)) {
            List<Object> pipelines = Json.list(Json.parseOrNull(http.get(projectPath + "/pipelines?ref="
                    + URLEncoder.encode(branch, StandardCharsets.UTF_8) + "&per_page=5").body()));
            if (!pipelines.isEmpty()) {
                pipeline = pipelines.getFirst();
                String status = Json.string(pipeline, "status").orElse("");
                jobs = Json.list(Json.parseOrNull(http.get(projectPath + "/pipelines/"
                        + Json.string(pipeline, "id").orElse("0") + "/jobs").body()));
                if (Set.of("success", "failed", "canceled", "skipped", "manual").contains(status)) {
                    break;
                }
            }
            sleep(POLL);
        }
        if (pipeline == null) {
            op("job_trace", "SKIPPED", "no pipeline for " + branch + " within " + PIPELINE_BOUND.toMinutes() + " min");
            return;
        }
        m20.put("pipeline", Map.of("id", Json.string(pipeline, "id").orElse(""), "status",
                Json.string(pipeline, "status").orElse(""), "url", Json.string(pipeline, "web_url").orElse(""),
                "jobs", jobs.size()));
        if (jobs.isEmpty()) {
            op("job_trace", "SKIPPED", "pipeline without jobs");
            return;
        }
        Object job = jobs.getFirst();
        jobId = Long.parseLong(Json.string(job, "id").orElseThrow());
        CiResult<String> trace = gitlab.jobTrace(jobId);
        op("job_trace", trace.outcome().name(), trace.detail() + trace.value().map(t -> " job=" + jobId + " chars="
                + t.length() + " status=" + Json.string(job, "status").orElse("")).orElse(""));
    }

    // --- M21 -----------------------------------------------------------------------------------------------------

    private void glabForms() {
        var glab = new GlabCommands(HOST, project);
        m21.put("glab", glabBinary());
        m21.put("glab_version", run(new GlabCommands.Invocation(List.of("glab", "--version"), Optional.empty()))
                .get("stdout"));
        String branch = "pm-fixture/m21-" + System.currentTimeMillis();
        String sha = commitOnNewBranch(branch, "m21");
        if (sha == null) {
            return;
        }
        String title = "Fixture run " + branch;
        String description = "Description from stdin\n\nline with \"quotes\" and --flags";
        var create = run(glab.createMergeRequest(branch, MAIN, title, description));
        Object mr = Json.parseOrNull(String.valueOf(create.get("stdout")));
        boolean created = title.equals(Json.string(mr, "title").orElse(null))
                && description.equals(Json.string(mr, "description").orElse(null));
        form("createMergeRequest", true, create, created ? "yes: MR title and description equal the stdin body"
                : "no");
        if (!created) {
            cleanup.add(() -> deleteBranch(branch));
            return;
        }
        long iid = Long.parseLong(Json.string(mr, "iid").orElseThrow());
        m21.put("mr_url", Json.string(mr, "web_url").orElse(""));
        cleanup.add(() -> closeIfOpen(iid, branch));
        String discussionId = startDiscussion(iid, "Fixture discussion " + branch);
        var listed = run(glab.discussions(iid));
        form("discussions", false, listed, "n/a (no body)");
        if (discussionId != null) {
            String replyBody = "Fixture reply from stdin\n\n- item";
            var reply = run(glab.reply(iid, discussionId, replyBody));
            boolean replied = replyBody.equals(Json.string(Json.parseOrNull(String.valueOf(reply.get("stdout"))), "body")
                    .orElse(null));
            form("reply", true, reply, replied ? "yes: note body equals the stdin body" : "no");
            var resolve = run(glab.resolve(iid, discussionId));
            form("resolve", false, resolve, "n/a (no body)");
        }
        if (jobId > 0) {
            form("jobTrace", false, run(glab.jobTrace(jobId)), "n/a (no body)");
        }
        form("tokenIdentity", false, run(glab.tokenIdentity()), "n/a (no body)");
        waitMergeable(iid, "m21_merge_status");
        var train = run(glab.mergeTrainAdd(iid, sha));
        form("mergeTrainAdd", true, train,
                "undeterminable: the endpoint's answer does not depend on the body (see merge for stdin evidence)");
        String wrongSha = "0000000000000000000000000000000000000000";
        var wrong = run(glab.merge(iid, wrongSha));
        boolean refusedBySha = String.valueOf(wrong.get("stdout")).contains("SHA")
                || String.valueOf(wrong.get("stderr")).contains("SHA");
        form("merge_wrong_sha", true, wrong, refusedBySha
                ? "yes: GitLab refused the merge on the stdin sha" : "unclear: no sha refusal");
        var merge = run(glab.merge(iid, sha));
        boolean merged = "merged".equals(Json.string(Json.parseOrNull(String.valueOf(merge.get("stdout"))), "state")
                .orElse(null));
        form("merge", true, merge, merged && refusedBySha ? "yes: merged only with the matching stdin sha" : "no");
    }

    private Map<String, Object> run(GlabCommands.Invocation invocation) {
        var argv = new ArrayList<>(invocation.argv());
        argv.set(0, glabBinary());
        var result = new LinkedHashMap<String, Object>();
        result.put("argv", invocation.argv());
        result.put("stdin_bytes", invocation.stdin().map(s -> s.getBytes(StandardCharsets.UTF_8).length).orElse(0));
        try {
            Path out = Files.createTempFile(temp, "glab", ".out");
            Path err = Files.createTempFile(temp, "glab", ".err");
            var builder = new ProcessBuilder(argv).directory(temp.toFile()).redirectOutput(out.toFile())
                    .redirectError(err.toFile());
            builder.environment().remove("GITLAB_TOKEN");
            builder.environment().remove(TOKEN_ENV);
            long start = System.nanoTime();
            Process process = builder.start();
            try (var stdin = process.getOutputStream()) {
                if (invocation.stdin().isPresent()) {
                    stdin.write(invocation.stdin().get().getBytes(StandardCharsets.UTF_8));
                }
            }
            boolean done = process.waitFor(60, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
            }
            result.put("exit", done ? process.exitValue() : -1);
            result.put("ms", (System.nanoTime() - start) / 1_000_000L);
            result.put("stdout", Files.readString(out).strip());
            result.put("stderr", Files.readString(err).strip());
        } catch (IOException e) {
            result.put("exit", -1);
            result.put("stderr", e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.put("exit", -1);
            result.put("stderr", "interrupted");
        }
        return result;
    }

    private void form(String name, boolean takesBody, Map<String, Object> run, String stdinRead) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("takes_body", takesBody);
        entry.put("argv", run.get("argv"));
        entry.put("stdin_bytes", run.get("stdin_bytes"));
        entry.put("exit", run.get("exit"));
        entry.put("ms", run.get("ms"));
        entry.put("stdout", truncate(String.valueOf(run.get("stdout")), 1500));
        entry.put("stderr", truncate(String.valueOf(run.get("stderr")), 1500));
        entry.put("stdin_read", stdinRead);
        forms.put(name, entry);
    }

    private static String glabBinary() {
        String configured = System.getProperty("spike.glab", "/opt/homebrew/bin/glab");
        return Files.isExecutable(Path.of(configured)) ? configured : "glab";
    }

    // --- shared REST setup (not under test) -------------------------------------------------------------------

    private String commitOnNewBranch(String branch, String item) {
        var action = new LinkedHashMap<String, Object>();
        action.put("action", "create");
        action.put("file_path", "fixture-runs/" + branch.substring(branch.lastIndexOf('/') + 1) + ".txt");
        action.put("content", "fixture run " + branch + "\n");
        var body = new LinkedHashMap<String, Object>();
        body.put("branch", branch);
        body.put("start_branch", MAIN);
        body.put("commit_message", "test: fixture run " + branch);
        body.put("actions", List.of(action));
        CiResponse response = http.send("POST", projectPath + "/repository/commits", Optional.of(Json.write(body)),
                Optional.empty());
        ("m20".equals(item) ? m20 : m21).put("setup_commit", response.outcome().name() + " " + response.status()
                + (response.isOk() ? "" : " " + truncate(response.body(), 500)));
        return response.isOk() ? Json.string(Json.parseOrNull(response.body()), "id").orElse(null) : null;
    }

    private Object createMergeRequest(String branch, String item) {
        var body = new LinkedHashMap<String, Object>();
        body.put("source_branch", branch);
        body.put("target_branch", MAIN);
        body.put("title", "Fixture run " + branch);
        body.put("description", "Automated verification run of plan-marshall-mcp (" + item + ").");
        body.put("remove_source_branch", Boolean.TRUE);
        CiResponse response = http.send("POST", projectPath + "/merge_requests", Optional.of(Json.write(body)),
                Optional.empty());
        op("mr_create", response.outcome().name(), response.status() + (response.isOk() ? ""
                : " " + truncate(response.body(), 500)));
        return response.isOk() ? Json.parseOrNull(response.body()) : null;
    }

    private String startDiscussion(long iid, String text) {
        CiResponse response = http.send("POST", projectPath + "/merge_requests/" + iid + "/discussions",
                Optional.of(Json.write(Map.of("body", text))), Optional.empty());
        return response.isOk() ? Json.string(Json.parseOrNull(response.body()), "id").orElse(null) : null;
    }

    private void waitMergeable(long iid, String key) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        String status = "";
        while (Instant.now().isBefore(deadline)) {
            status = Json.string(Json.parseOrNull(http.get(projectPath + "/merge_requests/" + iid).body()),
                    "detailed_merge_status").orElse("");
            if (!SETTLING.contains(status)) {
                break;
            }
            sleep(POLL);
        }
        m20.put(key, status);
    }

    private void closeIfOpen(long iid, String branch) {
        Object mr = Json.parseOrNull(http.get(projectPath + "/merge_requests/" + iid).body());
        if ("opened".equals(Json.string(mr, "state").orElse(""))) {
            http.send("PUT", projectPath + "/merge_requests/" + iid, Optional.of(Json.write(Map.of("state_event",
                    "close"))), Optional.empty());
        }
        deleteBranch(branch);
    }

    private void deleteBranch(String branch) {
        http.send("DELETE", projectPath + "/repository/branches/" + URLEncoder.encode(branch, StandardCharsets.UTF_8),
                Optional.empty(), Optional.empty());
    }

    // --- evaluation and output ------------------------------------------------------------------------------------

    private boolean evaluateM20() {
        m20.put("operations", ops);
        boolean identity = ok("token_identity_client_token") || ok("token_identity_fixture_token");
        // a definite provider answer (OK, or an HTTP refusal such as "not enabled") rather than a transport failure
        boolean train = ops.get("merge_train_add") instanceof Map<?, ?> t && ("OK".equals(t.get(OUTCOME))
                || String.valueOf(t.get(DETAIL)).startsWith("HTTP "));
        m20.put("merge_train_answered", train);
        m20.put("token_identity_answered", identity);
        return identity && train && ok("discussions_list") && ok("discussion_reply") && ok("discussion_resolve")
                && ok("job_trace") && ok("merge");
    }

    private boolean evaluateM21() {
        m21.put("forms", forms);
        return List.of("createMergeRequest", "reply", "merge").stream().allMatch(f -> forms.get(f) instanceof Map<?, ?> m
                && String.valueOf(m.get("stdin_read")).startsWith("yes"));
    }

    private boolean ok(String operation) {
        return ops.get(operation) instanceof Map<?, ?> map && "OK".equals(map.get(OUTCOME));
    }

    private void op(String name, String outcome, String detail) {
        ops.put(name, Map.of(OUTCOME, outcome, DETAIL, truncate(detail, 2000)));
    }

    private void write(String item, Map<String, Object> values, boolean pass) throws IOException {
        var root = new LinkedHashMap<String, Object>();
        root.put("item", item);
        root.put("os", System.getProperty("os.name"));
        root.put("arch", System.getProperty("os.arch"));
        root.put("values", values);
        root.put("pass", pass);
        String json = Json.write(root);
        for (String secret : secrets) {
            json = json.replace(secret, "***");
        }
        Path dir = Files.createDirectories(Path.of("target", "verification-results"));
        Files.writeString(dir.resolve(item + ".json"), json + "\n");
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static Path expand(String path) {
        return path.startsWith("~/") ? Path.of(System.getProperty("user.home"), path.substring(2)) : Path.of(path);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
