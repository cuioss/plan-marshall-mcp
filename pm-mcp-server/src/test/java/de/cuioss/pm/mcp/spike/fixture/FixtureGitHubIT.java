/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.fixture;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;


import de.cuioss.pm.provider.ci.CiEndpoint;
import de.cuioss.pm.provider.ci.CiHttpClient;
import de.cuioss.pm.provider.ci.CiResponse;
import de.cuioss.pm.provider.ci.CiResult;
import de.cuioss.pm.provider.ci.Json;
import de.cuioss.pm.provider.git.GitOperations.CommitInput;
import de.cuioss.pm.provider.git.GitOperations.GitCredential;
import de.cuioss.pm.provider.git.GitOperations.Identity;
import de.cuioss.pm.provider.git.GitOperations.PushInput;
import de.cuioss.pm.provider.git.GitOperations.RemoteInput;
import de.cuioss.pm.provider.git.GitOperations.WorktreeAddInput;
import de.cuioss.pm.provider.git.GitOutcome;
import de.cuioss.pm.provider.git.GitResult;
import de.cuioss.pm.provider.git.JGitOperations;
import de.cuioss.pm.provider.git.RemotePolicy;
import de.cuioss.pm.provider.github.GitHubAppJwt;
import de.cuioss.pm.provider.github.GitHubClient;
import de.cuioss.pm.provider.github.InstallationTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * M16 to M19 (doc/roadmap/technical_macos.adoc) against the operator's fixture repository: the product JGit
 * transport with an App installation token as HTTPS credential, the App token minting narrowed per operation class,
 * the PR, comment, review-thread, checks, statuses, actions and merge-queue operations, the CI workflow trigger of
 * an App-token push and PR, and the review bots' answer to a trigger comment posted by the App identity.
 * <p>
 * Lives in {@code pm-mcp-server} because it composes {@code pm-provider-git} (push) with {@code pm-provider-github}
 * (token), which only the assembly depends on together. Opt-in:
 * {@code -Dspike.fixture=~/.config/pm-mcp-fixture/app.json [-Dspike.botWaitMinutes=15]}. Figures:
 * {@code target/verification-results/m16-jgit-github.json}, {@code m17-review-bots.json},
 * {@code m18-ci-trigger.json}, {@code m19-app-permissions.json}.
 */
@EnabledIfSystemProperty(named = FixtureSettings.PROPERTY, matches = ".+")
@DisplayName("M16-M19: JGit transport and the GitHub App against the fixture repository")
class FixtureGitHubIT {

    private static final URI API = URI.create("https://api.github.com");
    private static final Map<String, String> HEADERS = Map.of("Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", "2022-11-28");
    private static final String WRITE = "write";
    private static final String READ = "read";
    private static final String CONTENTS = "contents";
    private static final String PULL_REQUESTS = "pull_requests";
    private static final String ISSUES = "issues";
    private static final Map<String, String> CONTENTS_READ = Map.of(CONTENTS, READ);
    private static final Map<String, String> CONTENTS_WRITE = Map.of(CONTENTS, WRITE);
    private static final Map<String, String> PR_READ = Map.of(PULL_REQUESTS, READ);
    private static final Map<String, String> PR_WRITE = Map.of(PULL_REQUESTS, WRITE);
    private static final Map<String, String> ISSUES_WRITE = Map.of(ISSUES, WRITE);
    private static final Map<String, String> CONVERSATION_READ = Map.of(ISSUES, READ, PULL_REQUESTS, READ);
    private static final Map<String, String> CHECKS_READ = Map.of("checks", READ);
    private static final Map<String, String> STATUSES_READ = Map.of("statuses", READ);
    private static final Map<String, String> ACTIONS_READ = Map.of("actions", READ);
    private static final Map<String, String> MERGE_QUEUE = Map.of(CONTENTS, WRITE, PULL_REQUESTS, WRITE);
    private static final Map<String, String> SPEC_PERMISSIONS = Map.of(CONTENTS, WRITE, PULL_REQUESTS, WRITE,
            ISSUES, WRITE, "checks", READ, "actions", READ, "statuses", READ, "metadata", READ);
    private static final List<String> BOTS = List.of("coderabbitai[bot]", "sourcery-ai[bot]",
            "cuioss-review-bot[bot]");
    private static final Pattern ANSWER = Pattern.compile("(?i)actions performed|review triggered");
    private static final Pattern NOTICE = Pattern.compile(
            "(?i)rate[ -]?limit|skipped|skipping|paused|limit (exceeded|reached)|quota|ignored|bot user");
    private static final Pattern SKIP = Pattern.compile("(?i)review skipped");
    private static final String SILENT = "silent";
    private static final String SKIP_NOTICE_ONLY = "skip_notice_only";
    private static final Duration POLL = Duration.ofSeconds(30);
    private static final String TRIGGER = "@coderabbitai review";
    private static final String OUTCOME = "outcome";
    private static final String DETAIL = "detail";
    private static final String STATUS = "status";
    private static final String CREATED_AT = "created_at";
    private static final String HEAD_SHA = "head_sha";

    @TempDir
    Path temp;

    private FixtureSettings settings;
    private final FixtureResults results = new FixtureResults();
    private InstallationTokens tokens;
    private final List<CiHttpClient> clients = new ArrayList<>();
    private final Set<String> otherBots = new TreeSet<>();
    private final Map<String, Object> m16 = new LinkedHashMap<>();
    private final Map<String, Object> m17 = new LinkedHashMap<>();
    private final Map<String, Object> m18 = new LinkedHashMap<>();
    private final Map<String, Object> m19 = new LinkedHashMap<>();
    private final Map<String, Object> operations = new LinkedHashMap<>();
    private final Map<String, Object> mints = new LinkedHashMap<>();
    private String repoPath;
    private String branch;
    private String file;
    private String firstSha;
    private String headSha;
    private int number;
    private String nodeId;
    private Instant triggerAt;

    @Test
    @DisplayName("runs JGit, the App token operations, the CI trigger and the review bots against the fixture")
    void shouldVerifyAgainstFixture() throws Exception {
        settings = FixtureSettings.load();
        repoPath = "repos/" + settings.owner() + "/" + settings.repository();
        branch = "pm-fixture/m17-" + System.currentTimeMillis();
        file = "fixture-runs/" + branch.substring(branch.lastIndexOf('/') + 1) + ".txt";
        tokens = new InstallationTokens(CiEndpoint.of(API), settings.clientId(), settings::privateKeyPem, results::secret,
                Clock.systemUTC());
        m19.put("key_format", settings.keyFormat());
        boolean[] pass = new boolean[4];
        try {
            installationPermissions();
            jgitScenario();
            if (headSha != null) {
                pullRequestScenario();
            }
        } finally {
            cleanup();
            pass[0] = evaluateM16();
            pass[1] = evaluateM17();
            pass[2] = evaluateM18();
            pass[3] = evaluateM19();
            results.write("m16-jgit-github", m16, pass[0]);
            results.write("m17-review-bots", m17, pass[1]);
            results.write("m18-ci-trigger", m18, pass[2]);
            results.write("m19-app-permissions", m19, pass[3]);
            clients.forEach(CiHttpClient::close);
            tokens.close();
        }
        assertAll(() -> assertTrue(pass[0], "M16"), () -> assertTrue(pass[1], "M17"),
                () -> assertTrue(pass[2], "M18"), () -> assertTrue(pass[3], "M19"));
    }

    // --- App installation ---------------------------------------------------------------------------------------

    private void installationPermissions() {
        String pem = settings.privateKeyPem();
        try (var app = new CiHttpClient(CiEndpoint.of(API),
                     () -> Optional.of(GitHubAppJwt.sign(settings.clientId(), pem, Instant.now())), HEADERS)) {
            CiResponse response = app.get("app/installations/" + settings.installationId());
            Object json = Json.parseOrNull(response.body());
            var installation = new LinkedHashMap<String, Object>();
            installation.put(STATUS, response.status());
            installation.put("permissions", Json.at(json, "permissions").orElse(null));
            installation.put("repository_selection", Json.string(json, "repository_selection").orElse(""));
            installation.put("app_slug", Json.string(json, "app_slug").orElse(""));
            m19.put("installation", installation);
            m19.put("installation_equals_spec_permissions",
                    SPEC_PERMISSIONS.equals(Json.at(json, "permissions").orElse(null)));
        }
        CiResult<InstallationTokens.InstallationToken> wider = tokens.token(settings.installationId(),
                settings.repository(), Map.of("administration", READ));
        m19.put("mint_beyond_installation", Map.of(OUTCOME, wider.outcome().name(), DETAIL, wider.detail()));
    }

    // --- M16: JGit against the real remote --------------------------------------------------------------------

    private void jgitScenario() throws IOException {
        var git = new JGitOperations(RemotePolicy.httpsOrigin(URI.create("https://github.com")));
        Path root = temp.resolve("clone");
        Path worktree = root.resolve("wt").resolve("m17");
        var steps = new LinkedHashMap<String, Object>();
        m16.put("steps", steps);
        m16.put("clone_operation", "none in GitOperations: init + configured remote + fetch");
        if (!timed(steps, "init", () -> git.init(root, "main")).isOk()) {
            return;
        }
        Files.writeString(root.resolve(".git").resolve("config"), "[remote \"origin\"]\n\turl = https://github.com/"
                + settings.owner() + "/" + settings.repository() + ".git\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n",
                StandardOpenOption.APPEND);
        Optional<GitCredential> read = credential("git_fetch", CONTENTS_READ);
        Optional<GitCredential> write = credential("git_push", CONTENTS_WRITE);
        var fetched = timed(steps, "fetch", () -> git.fetch(new RemoteInput(root, "origin", List.of(), read)));
        fetched.value().ifPresent(updates -> m16.put("fetched_refs", updates.size()));
        timed(steps, "log_origin_main", () -> git.log(root, "refs/remotes/origin/main", 1000))
                .value().ifPresent(log -> {
            m16.put("origin_main_commits", log.size());
            m16.put("origin_main_sha", log.getFirst().id());
        });
        if (!timed(steps, "worktree_add", () -> git.worktreeAdd(new WorktreeAddInput(root, worktree, Optional.of(branch),
                true, "refs/remotes/origin/main"))).isOk()) {
            return;
        }
        var agent = new Identity(settings.slug() + "[bot]", settings.appId() + "+" + settings.slug()
                + "[bot]@users.noreply.github.com");
        Files.createDirectories(worktree.resolve(file).getParent());
        Files.writeString(worktree.resolve(file), "fixture run " + branch + "\n", StandardCharsets.UTF_8);
        var first = timed(steps, "commit_1", () -> git.commit(new CommitInput(worktree, "test: fixture run " + branch,
                agent, agent)));
        firstSha = first.value().map(c -> c.id()).orElse(null);
        var pushed = timed(steps, "push_1", () -> git.push(new PushInput(worktree, "origin", "HEAD",
                "refs/heads/" + branch, Optional.empty(), write)));
        if (!pushed.isOk()) {
            m19ops("contents_write_push", CONTENTS_WRITE, pushed.outcome().name(), pushed.detail());
            return;
        }
        Files.writeString(worktree.resolve(file), "second line\n", StandardOpenOption.APPEND);
        var second = timed(steps, "commit_2", () -> git.commit(new CommitInput(worktree, "test: second change",
                agent, agent)));
        String secondSha = second.value().map(c -> c.id()).orElse(null);
        String staleLease = String.valueOf(m16.get("origin_main_sha"));
        timed(steps, "push_lease_stale", () -> git.push(new PushInput(worktree, "origin", "HEAD",
                "refs/heads/" + branch, Optional.of(staleLease), write)));
        var leased = timed(steps, "push_lease_current", () -> git.push(new PushInput(worktree, "origin", "HEAD",
                "refs/heads/" + branch, Optional.of(firstSha), write)));
        m19ops("contents_write_push", CONTENTS_WRITE, leased.outcome().name(), leased.detail());
        if (leased.isOk()) {
            headSha = secondSha;
        }
        timed(steps, "fetch_after_push", () -> git.fetch(new RemoteInput(worktree, "origin", List.of(), read)))
                .value().ifPresent(updates -> m16.put("fetch_after_push_updates",
                updates.stream().map(u -> u.ref() + " " + u.status()).toList()));
        timed(steps, "log_head", () -> git.log(worktree, "HEAD", 5));
        timed(steps, "worktree_sha", () -> git.worktreeSha(worktree));
        timed(steps, "worktree_list", () -> git.worktreeList(root)).value()
                .ifPresent(list -> m16.put("worktrees", list.size()));
        Files.writeString(root.resolve(".git").resolve("config"),
                "[remote \"foreign\"]\n\turl = https://gitlab.com/cuioss/pm-mcp-fixture.git\n", StandardOpenOption.APPEND);
        timed(steps, "push_foreign_origin", () -> git.push(new PushInput(root, "foreign", "refs/heads/" + branch,
                "refs/heads/" + branch, Optional.empty(), write)));
        timed(steps, "worktree_remove", () -> git.worktreeRemove(root, worktree));
        m16.put("first_sha", firstSha);
        m16.put("head_sha", headSha);
        m16.put("branch", branch);
    }

    private <T> GitResult<T> timed(Map<String, Object> steps, String name, Supplier<GitResult<T>> step) {
        long start = System.nanoTime();
        GitResult<T> result = step.get();
        var entry = new LinkedHashMap<String, Object>();
        entry.put(OUTCOME, result.outcome().name());
        entry.put("ms", (System.nanoTime() - start) / 1_000_000L);
        if (!result.detail().isEmpty()) {
            entry.put(DETAIL, result.detail());
        }
        steps.put(name, entry);
        return result;
    }

    private Optional<GitCredential> credential(String operation, Map<String, String> permissions) {
        return token(operation, permissions).map(t -> new GitCredential("x-access-token", t.toCharArray()));
    }

    private boolean evaluateM16() {
        @SuppressWarnings("unchecked") var steps = (Map<String, Object>) m16.getOrDefault("steps", Map.of());
        List<String> mustPass = List.of("init", "fetch", "log_origin_main", "worktree_add", "commit_1", "push_1",
                "commit_2", "push_lease_current", "fetch_after_push", "log_head", "worktree_sha", "worktree_list",
                "worktree_remove");
        boolean ok = mustPass.stream().allMatch(s -> GitOutcome.OK.name().equals(outcomeOf(steps.get(s))));
        boolean lease = GitOutcome.REJECTED_LEASE.name().equals(outcomeOf(steps.get("push_lease_stale")));
        boolean foreign = GitOutcome.REMOTE_ORIGIN_MISMATCH.name().equals(outcomeOf(steps.get("push_foreign_origin")));
        m16.put("runtime", "JVM (not the native pm-mcpd)");
        return ok && lease && foreign;
    }

    private static String outcomeOf(Object step) {
        return step instanceof Map<?, ?> map ? String.valueOf(map.get(OUTCOME)) : "MISSING";
    }

    // --- M17 to M19: the pull request ---------------------------------------------------------------------------

    private void pullRequestScenario() {
        Optional<CiHttpClient> prWrite = client("pr_create", PR_WRITE);
        if (prWrite.isEmpty()) {
            return;
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("title", "Fixture run " + branch);
        body.put("head", branch);
        body.put("base", "main");
        body.put("body", "Automated verification run of plan-marshall-mcp (M17-M19). Closed by the run.");
        CiResponse created = prWrite.get().send("POST", repoPath + "/pulls", Optional.of(Json.write(body)),
                Optional.empty());
        m19ops("pr_create", PR_WRITE, created);
        if (!created.isOk()) {
            return;
        }
        Object pr = Json.parse(created.body());
        number = Integer.parseInt(Json.string(pr, "number").orElseThrow());
        nodeId = Json.string(pr, "node_id").orElseThrow();
        m17.put("pr_url", Json.string(pr, "html_url").orElse(""));
        m17.put("pr_author", Json.string(pr, "user", "login").orElse(""));
        m18.put("pr_url", m17.get("pr_url"));
        m18.put("pr_head_sha", Json.string(pr, "head", "sha").orElse(""));
        trigger();
        reviewThread(prWrite.get());
        enqueue();
        waitForBotsAndCi();
        checksAndStatuses();
    }

    private void trigger() {
        Optional<CiHttpClient> issuesWrite = client("comment_issues_write", ISSUES_WRITE);
        CiResult<String> comment = issuesWrite.map(c -> new GitHubClient(c, GitHubClient.GRAPHQL_PATH)
                .postComment(settings.owner(), settings.repository(), number, TRIGGER))
                .orElse(CiResult.of(CiResult.Outcome.UNAUTHORIZED, "no token"));
        m19ops("comment_issues_write", ISSUES_WRITE, comment.outcome().name(), comment.detail());
        if (!comment.isOk()) {
            Optional<CiHttpClient> prWrite = client("comment_pull_requests_write", PR_WRITE);
            comment = prWrite.map(c -> new GitHubClient(c, GitHubClient.GRAPHQL_PATH)
                    .postComment(settings.owner(), settings.repository(), number, TRIGGER))
                    .orElse(CiResult.of(CiResult.Outcome.UNAUTHORIZED, "no token"));
            m19ops("comment_pull_requests_write", PR_WRITE, comment.outcome().name(), comment.detail());
        }
        if (!comment.isOk()) {
            return;
        }
        String id = comment.value().orElseThrow();
        client("conversation_read", CONVERSATION_READ).ifPresent(c -> {
            Object json = Json.parseOrNull(c.get(repoPath + "/issues/comments/" + id).body());
            triggerAt = Json.string(json, CREATED_AT).map(Instant::parse).orElse(Instant.now());
            m17.put("trigger_comment", Map.of("id", id, "body", TRIGGER, "author", Json.string(json, "user", "login")
                    .orElse(""), CREATED_AT, triggerAt.toString(), "url", Json.string(json, "html_url").orElse("")));
        });
    }

    private void reviewThread(CiHttpClient prWrite) {
        var body = new LinkedHashMap<String, Object>();
        String text = "Fixture review thread " + branch;
        body.put("body", text);
        body.put("commit_id", headSha);
        body.put("path", file);
        body.put("line", 1);
        body.put("side", "RIGHT");
        CiResponse created = prWrite.send("POST", repoPath + "/pulls/" + number + "/comments",
                Optional.of(Json.write(body)), Optional.empty());
        m19ops("review_comment_create", PR_WRITE, created);
        Optional<CiHttpClient> prRead = client("review_threads_list", PR_READ);
        if (prRead.isEmpty()) {
            return;
        }
        var threads = new GitHubClient(prRead.get(), GitHubClient.GRAPHQL_PATH).reviewThreads(settings.owner(),
                settings.repository(), number);
        m19ops("review_threads_list", PR_READ, threads.outcome().name(),
                threads.detail() + threads.value().map(t -> " threads=" + t.size()).orElse(""));
        Optional<GitHubClient.ReviewThread> own = threads.value().orElse(List.of()).stream()
                .filter(t -> t.comments().stream().anyMatch(c -> text.equals(c.body()))).findFirst();
        if (own.isEmpty()) {
            m19ops("review_thread_resolve", PR_WRITE, "SKIPPED", "own thread not found");
            return;
        }
        CiResult<Boolean> resolved = new GitHubClient(prWrite, GitHubClient.GRAPHQL_PATH)
                .resolveReviewThread(own.get().id());
        m19ops("review_thread_resolve", PR_WRITE, resolved.outcome().name(),
                resolved.detail() + resolved.value().map(r -> " isResolved=" + r).orElse(""));
    }

    private void enqueue() {
        Optional<CiHttpClient> queue = client("merge_queue_enqueue", MERGE_QUEUE);
        if (queue.isEmpty()) {
            return;
        }
        CiResult<GitHubClient.MergeQueueEntry> entry = new GitHubClient(queue.get(), GitHubClient.GRAPHQL_PATH)
                .enqueue(nodeId, headSha);
        m19ops("merge_queue_enqueue", MERGE_QUEUE, entry.outcome().name(),
                entry.detail() + entry.value().map(e -> " state=" + e.state()).orElse(""));
    }

    private void waitForBotsAndCi() {
        Optional<CiHttpClient> read = client("conversation_read", CONVERSATION_READ);
        Optional<CiHttpClient> actions = client("actions_read", ACTIONS_READ);
        if (read.isEmpty() || actions.isEmpty()) {
            return;
        }
        Duration bound = Duration.ofMinutes(Long.getLong("spike.botWaitMinutes", 15));
        Instant start = Instant.now();
        Instant deadline = start.plus(bound);
        Map<String, List<Map<String, Object>>> activity;
        List<Map<String, Object>> runs;
        while (true) {
            var polled = botActivity(read.get());
            var polledRuns = runs(actions.get());
            activity = polled;
            runs = polledRuns;
            boolean botsDone = BOTS.stream().allMatch(b -> !polled.get(b).isEmpty())
                    && answered(polled.get(BOTS.getFirst()));
            boolean ciDone = polledRuns.stream().anyMatch(r -> "push".equals(r.get("event")))
                    && polledRuns.stream().anyMatch(r -> "pull_request".equals(r.get("event")))
                    && polledRuns.stream().allMatch(r -> "completed".equals(r.get(STATUS)));
            if ((botsDone && ciDone) || Instant.now().isAfter(deadline)) {
                break;
            }
            sleep(POLL);
        }
        m17.put("waited_seconds", Duration.between(start, Instant.now()).toSeconds());
        m17.put("wait_bound_minutes", bound.toMinutes());
        var perBot = new LinkedHashMap<String, Object>();
        for (String bot : BOTS) {
            List<Map<String, Object>> events = activity.get(bot);
            var entry = new LinkedHashMap<String, Object>();
            boolean skipOnly = !events.isEmpty() && events.stream()
                    .allMatch(e -> SKIP.matcher(String.valueOf(e.get("body"))).find());
            entry.put("classification", answered(events) ? "answered_app_trigger"
                    : events.isEmpty() ? SILENT : skipOnly ? SKIP_NOTICE_ONLY : "reviewed_on_push_only");
            entry.put("events", events.stream().map(e -> {
                var copy = new LinkedHashMap<>(e);
                copy.remove("body");
                return copy;
            }).toList());
            entry.put("notices", events.stream().map(e -> String.valueOf(e.get("body")))
                    .filter(b -> NOTICE.matcher(b).find()).toList());
            perBot.put(bot, entry);
        }
        m17.put("bots", perBot);
        m17.put("other_bots_seen", List.copyOf(otherBots));
        m18.put("runs", runs);
        m18.put("push_shas", List.of(String.valueOf(firstSha), String.valueOf(headSha)));
        m19ops("actions_read", ACTIONS_READ, runs.isEmpty() ? "EMPTY" : "OK", "runs=" + runs.size());
    }

    private boolean answered(List<Map<String, Object>> events) {
        return triggerAt != null && events.stream().anyMatch(e -> !Instant.parse(String.valueOf(e.get(CREATED_AT)))
                .isBefore(triggerAt) && ANSWER.matcher(String.valueOf(e.get("body"))).find());
    }

    private Map<String, List<Map<String, Object>>> botActivity(CiHttpClient read) {
        var activity = new LinkedHashMap<String, List<Map<String, Object>>>();
        BOTS.forEach(b -> activity.put(b, new ArrayList<>()));
        collect(read, "/issues/" + number + "/comments?per_page=100", "issue_comment", CREATED_AT, activity);
        collect(read, "/pulls/" + number + "/reviews?per_page=100", "review", "submitted_at", activity);
        collect(read, "/pulls/" + number + "/comments?per_page=100", "review_comment", CREATED_AT, activity);
        return activity;
    }

    private void collect(CiHttpClient read, String path, String kind, String timeField,
            Map<String, List<Map<String, Object>>> activity) {
        for (String page : read.getAll(repoPath + path, 10).pages()) {
            for (Object item : Json.list(Json.parseOrNull(page))) {
                String login = Json.string(item, "user", "login").orElse("");
                if (login.endsWith("[bot]") && !activity.containsKey(login)
                        && !login.equals(settings.slug() + "[bot]")) {
                    otherBots.add(login);
                }
                if (activity.containsKey(login)) {
                    var event = new LinkedHashMap<String, Object>();
                    event.put("kind", kind);
                    event.put("id", Json.string(item, "id").orElse(""));
                    event.put(CREATED_AT, Json.string(item, timeField).orElse(Instant.EPOCH.toString()));
                    Json.string(item, "state").ifPresent(s -> event.put("state", s));
                    String body = Json.string(item, "body").orElse("");
                    event.put("excerpt", body.length() > 400 ? body.substring(0, 400) : body);
                    event.put("after_trigger", triggerAt != null
                            && !Instant.parse(String.valueOf(event.get(CREATED_AT))).isBefore(triggerAt));
                    event.put("body", body.length() > 8000 ? body.substring(0, 8000) : body);
                    activity.get(login).add(event);
                }
            }
        }
    }

    private List<Map<String, Object>> runs(CiHttpClient actions) {
        var runs = new ArrayList<Map<String, Object>>();
        for (String sha : List.of(String.valueOf(firstSha), String.valueOf(headSha))) {
            Object json = Json.parseOrNull(actions.get(repoPath + "/actions/runs?head_sha=" + sha + "&per_page=50")
                    .body());
            for (Object run : Json.list(json, "workflow_runs")) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("id", Json.string(run, "id").orElse(""));
                entry.put("name", Json.string(run, "name").orElse(""));
                entry.put("event", Json.string(run, "event").orElse(""));
                entry.put(HEAD_SHA, Json.string(run, HEAD_SHA).orElse(""));
                entry.put(STATUS, Json.string(run, STATUS).orElse(""));
                entry.put("conclusion", Json.string(run, "conclusion").orElse(""));
                entry.put("actor", Json.string(run, "actor", "login").orElse(""));
                entry.put("triggering_actor", Json.string(run, "triggering_actor", "login").orElse(""));
                entry.put("url", Json.string(run, "html_url").orElse(""));
                runs.add(entry);
            }
        }
        return runs;
    }

    private void checksAndStatuses() {
        client("checks_read", CHECKS_READ).ifPresent(c -> {
            CiResponse checks = c.get(repoPath + "/commits/" + headSha + "/check-runs");
            m19ops("checks_read", CHECKS_READ, checks.outcome().name(), checks.isOk()
                    ? "check_runs=" + Json.string(Json.parseOrNull(checks.body()), "total_count").orElse("?")
                    : checks.detail() + " " + checks.body());
        });
        client("statuses_read", STATUSES_READ).ifPresent(c -> {
            CiResponse statuses = c.get(repoPath + "/commits/" + headSha + "/status");
            m19ops("statuses_read", STATUSES_READ, statuses.outcome().name(), statuses.isOk()
                    ? "state=" + Json.string(Json.parseOrNull(statuses.body()), "state").orElse("?")
                    : statuses.detail() + " " + statuses.body());
        });
    }

    // --- cleanup and evaluation --------------------------------------------------------------------------------

    private void cleanup() {
        var cleanup = new LinkedHashMap<String, Object>();
        if (number > 0) {
            client("pr_close", PR_WRITE).ifPresent(c -> {
                CiResponse closed = c.send("PATCH", repoPath + "/pulls/" + number,
                        Optional.of(Json.write(Map.of("state", "closed"))), Optional.empty());
                cleanup.put("pr_close", closed.outcome().name() + " " + closed.status());
            });
        }
        if (firstSha != null) {
            client("branch_delete", CONTENTS_WRITE).ifPresent(c -> {
                CiResponse deleted = c.send("DELETE", repoPath + "/git/refs/heads/" + branch, Optional.empty(),
                        Optional.empty());
                cleanup.put("branch_delete", deleted.outcome().name() + " " + deleted.status());
            });
        }
        m19.put("cleanup", cleanup);
    }

    private boolean evaluateM17() {
        if (!(m17.get("bots") instanceof Map<?, ?> bots)) {
            return false;
        }
        // a bot that only posts a skip notice did not review: it counts as silent
        return bots.values().stream().noneMatch(b -> b instanceof Map<?, ?> m
                && (SILENT.equals(m.get("classification")) || SKIP_NOTICE_ONLY.equals(m.get("classification"))));
    }

    private boolean evaluateM18() {
        if (!(m18.get("runs") instanceof List<?> runs)) {
            return false;
        }
        boolean push = runs.stream().anyMatch(r -> r instanceof Map<?, ?> m && "push".equals(m.get("event")));
        boolean pullRequest = runs.stream().anyMatch(r -> r instanceof Map<?, ?> m
                && "pull_request".equals(m.get("event")) && String.valueOf(headSha).equals(m.get(HEAD_SHA)));
        m18.put("push_run", push);
        m18.put("pull_request_run", pullRequest);
        return push && pullRequest;
    }

    private boolean evaluateM19() {
        m19.put("mints", mints);
        m19.put("operations", operations);
        List<String> required = List.of("contents_write_push", "pr_create", "review_comment_create",
                "review_threads_list", "review_thread_resolve", "checks_read", "statuses_read", "actions_read");
        boolean ok = required.stream().allMatch(o -> "OK".equals(outcomeOf(operations.get(o))));
        boolean comment = "OK".equals(outcomeOf(operations.get("comment_issues_write")))
                || "OK".equals(outcomeOf(operations.get("comment_pull_requests_write")));
        String queue = String.valueOf(operations.get("merge_queue_enqueue"));
        boolean queueNotPermission = !queue.contains("not accessible by integration") && !queue.contains("UNAUTHORIZED");
        return ok && comment && queueNotPermission;
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private Optional<String> token(String operation, Map<String, String> permissions) {
        CiResult<InstallationTokens.InstallationToken> token = tokens.token(settings.installationId(),
                settings.repository(), permissions);
        mints.put(operation, Map.of("permissions", new LinkedHashMap<>(permissions), OUTCOME, token.outcome().name(),
                DETAIL, token.detail()));
        return token.value().map(InstallationTokens.InstallationToken::token);
    }

    private Optional<CiHttpClient> client(String operation, Map<String, String> permissions) {
        return token(operation, permissions).map(t -> {
            var client = new CiHttpClient(CiEndpoint.of(API), () -> Optional.of(t), HEADERS);
            clients.add(client);
            return client;
        });
    }

    private void m19ops(String operation, Map<String, String> permissions, CiResponse response) {
        m19ops(operation, permissions, response.outcome().name(), response.isOk() ? "HTTP " + response.status()
                : response.detail() + " " + response.body());
    }

    private void m19ops(String operation, Map<String, String> permissions, String outcome, String detail) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("permissions", new LinkedHashMap<>(permissions));
        entry.put(OUTCOME, outcome);
        entry.put(DETAIL, results.redact(detail.length() > 2000 ? detail.substring(0, 2000) : detail));
        operations.put(operation, entry);
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
