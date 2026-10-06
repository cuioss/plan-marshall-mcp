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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
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
import de.cuioss.pm.provider.git.GitResult;
import de.cuioss.pm.provider.git.JGitOperations;
import de.cuioss.pm.provider.git.RemotePolicy;
import de.cuioss.pm.provider.github.GitHubClient;
import de.cuioss.pm.provider.github.InstallationTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * M17 to M19 (doc/roadmap/technical_macos.adoc) with a GitHub App user access token (user-to-server token from the
 * device flow) instead of the installation token: the actor behind the token, the push and pull request it makes, the
 * review bots' auto-review and their answer to a trigger comment posted with it, the hybrid of an App-authored pull
 * request with a user-token trigger, the CI trigger, the operations of the M19 table, and the refresh of the token.
 * <p>
 * Opt-in: {@code -Dspike.fixture=~/.config/pm-mcp-fixture/app.json
 * -Dspike.user-token=~/.config/pm-mcp-fixture/user-token.json [-Dspike.botWaitMinutes=15]
 * [-Dspike.triggerWaitMinutes=10]}. The refresh writes the new token pair back into the token file (mode 0600).
 * Figures: {@code target/verification-results/m17-user.json}, {@code m17-user-hybrid.json}, {@code m18-user.json},
 * {@code m19-user.json}.
 */
@EnabledIfSystemProperty(named = FixtureSettings.PROPERTY, matches = ".+")
@EnabledIfSystemProperty(named = FixtureGitHubUserTokenIT.USER_TOKEN, matches = ".+")
@DisplayName("M17-M19 with a GitHub App user access token against the fixture repository")
class FixtureGitHubUserTokenIT {

    /** The opt-in system property naming the user token file. */
    static final String USER_TOKEN = "spike.user-token";

    private static final URI API = URI.create("https://api.github.com");
    private static final URI WEB = URI.create("https://github.com");
    private static final Map<String, String> HEADERS = Map.of("Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", "2022-11-28");
    private static final Map<String, String> CONTENTS_WRITE = Map.of("contents", "write");
    private static final Map<String, String> PR_WRITE = Map.of("pull_requests", "write");
    private static final List<String> BOTS = List.of("coderabbitai[bot]", "sourcery-ai[bot]",
            "cuioss-review-bot[bot]");
    private static final String CODERABBIT = BOTS.getFirst();
    private static final Pattern ANSWER = Pattern.compile("(?i)actions performed|review triggered");
    private static final Pattern SKIP = Pattern.compile("(?i)review skipped|skipped review|skipping review");
    private static final Duration POLL = Duration.ofSeconds(30);
    private static final String TRIGGER = "@coderabbitai review";
    private static final int EXCERPT = 300;
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String EXPIRES_IN = "expires_in";
    private static final String REFRESH_EXPIRES_IN = "refresh_token_expires_in";
    private static final String OUTCOME = "outcome";
    private static final String DETAIL = "detail";
    private static final String STATUS = "status";
    private static final String CREATED_AT = "created_at";
    private static final String HEAD_SHA = "head_sha";
    private static final String LOGIN = "login";
    private static final String SILENT = "silent";
    private static final String EVENTS = "events";

    @TempDir
    Path temp;

    private FixtureSettings settings;
    private final FixtureResults results = new FixtureResults();
    private InstallationTokens tokens;
    private final List<CiHttpClient> clients = new ArrayList<>();
    private final Set<String> otherBots = new TreeSet<>();
    private final Map<String, Object> m17 = new LinkedHashMap<>();
    private final Map<String, Object> m18 = new LinkedHashMap<>();
    private final Map<String, Object> m19 = new LinkedHashMap<>();
    private final Map<String, Object> operations = new LinkedHashMap<>();
    private String repoPath;
    private Path tokenFile;
    private String userToken;
    private String refreshToken;
    private CiHttpClient user;
    private String login;
    private String userId;
    private Case userCase;
    private Case appCase;

    /** One pull request of the run: the user-token one, or the App-authored one of the hybrid. */
    private static final class Case {
        private final String name;
        private final String branch;
        private final String file;
        private final Map<String, Object> values = new LinkedHashMap<>();
        private String headSha;
        private int number;
        private String nodeId;
        private String triggerId;
        private Instant triggerAt;
        private Map<String, List<Map<String, Object>>> activity = Map.of();

        private Case(String name, String branch) {
            this.name = name;
            this.branch = branch;
            this.file = "fixture-runs/" + branch.substring(branch.lastIndexOf('/') + 1) + ".txt";
        }
    }

    @Test
    @DisplayName("runs push, pull request, bot triggers, CI and permission checks with the user token and refreshes it")
    void shouldVerifyUserTokenAgainstFixture() throws Exception {
        settings = FixtureSettings.load();
        repoPath = "repos/" + settings.owner() + "/" + settings.repository();
        tokenFile = FixtureSettings.expand(System.getProperty(USER_TOKEN));
        Object stored = Json.parse(Files.readString(tokenFile));
        userToken = Json.string(stored, ACCESS_TOKEN).orElseThrow();
        refreshToken = Json.string(stored, REFRESH_TOKEN).orElseThrow();
        results.secret(userToken);
        results.secret(refreshToken);
        tokens = new InstallationTokens(CiEndpoint.of(API), settings.clientId(), settings::privateKeyPem, results::secret,
                Clock.systemUTC());
        String token = userToken;
        user = track(new CiHttpClient(CiEndpoint.of(API), () -> Optional.of(token), HEADERS));
        long stamp = System.currentTimeMillis();
        userCase = new Case("user", "pm-fixture/m17u-" + stamp);
        appCase = new Case("hybrid", "pm-fixture/m17h-" + stamp);
        Map<String, Object> hybrid = appCase.values;
        boolean[] pass = new boolean[4];
        try {
            actor();
            if (login != null && pushBranches()) {
                openPullRequests();
                if (userCase.number > 0) {
                    userOperations();
                    waitForAutoReviews();
                    triggers();
                    waitForAnswers();
                    checksAndStatuses();
                }
            }
        } finally {
            cleanup();
            refresh();
            m17.putAll(userCase.values);
            pass[0] = evaluateM17(userCase);
            pass[1] = evaluateM17(appCase);
            pass[2] = evaluateM18();
            pass[3] = evaluateM19();
            results.write("m17-user", m17, pass[0]);
            results.write("m17-user-hybrid", hybrid, pass[1]);
            results.write("m18-user", m18, pass[2]);
            results.write("m19-user", m19, pass[3]);
            clients.forEach(CiHttpClient::close);
            tokens.close();
        }
        assertAll(() -> assertTrue(pass[0], "M17-user"), () -> assertTrue(pass[1], "M17-user-hybrid"),
                () -> assertTrue(pass[2], "M18-user"), () -> assertTrue(pass[3], "M19-user"));
    }

    // --- the actor ----------------------------------------------------------------------------------------------

    private void actor() {
        CiResponse me = user.get("user");
        ops("user_get", me);
        Object json = Json.parseOrNull(me.body());
        login = Json.string(json, LOGIN).orElse(null);
        userId = Json.string(json, "id").orElse("");
        String type = Json.string(json, "type").orElse("");
        var actor = new LinkedHashMap<String, Object>();
        actor.put(STATUS, me.status());
        actor.put(LOGIN, String.valueOf(login));
        actor.put("id", userId);
        actor.put("type", type);
        actor.put("is_user", "User".equals(type));
        m17.put("actor", actor);
        m19.put("actor", actor);
        CiResponse installations = user.get("user/installations");
        ops("user_installations", installations);
        m19.put("user_installations", Json.list(Json.parseOrNull(installations.body()), "installations").stream()
                .map(i -> Map.of("id", Json.string(i, "id").orElse(""), "app_slug",
                        Json.string(i, "app_slug").orElse(""), "account", Json.string(i, "account", LOGIN).orElse(""),
                        "repository_selection", Json.string(i, "repository_selection").orElse("")))
                .toList());
        CiResponse repos = user.get("user/installations/" + settings.installationId() + "/repositories");
        ops("user_installation_repositories", repos);
        m19.put("user_installation_repositories", Json.list(Json.parseOrNull(repos.body()), "repositories").stream()
                .map(r -> Json.string(r, "full_name").orElse("")).toList());
    }

    // --- pushes -------------------------------------------------------------------------------------------------

    private boolean pushBranches() throws IOException {
        var git = new JGitOperations(RemotePolicy.httpsOrigin(WEB));
        Path root = temp.resolve("clone");
        var steps = new LinkedHashMap<String, Object>();
        m19.put("jgit_steps", steps);
        if (!timed(steps, "init", () -> git.init(root, "main")).isOk()) {
            return false;
        }
        Files.writeString(root.resolve(".git").resolve("config"), "[remote \"origin\"]\n\turl = https://github.com/"
                + settings.owner() + "/" + settings.repository() + ".git\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n",
                StandardOpenOption.APPEND);
        Optional<GitCredential> userCredential = Optional.of(new GitCredential(login, userToken.toCharArray()));
        var fetched = timed(steps, "fetch_user", () -> git.fetch(new RemoteInput(root, "origin", List.of(),
                userCredential)));
        ops("contents_read_fetch", fetched.outcome().name(), fetched.detail());
        if (!fetched.isOk()) {
            return false;
        }
        var operator = new Identity(login, userId + "+" + login + "@users.noreply.github.com");
        GitResult<?> userPush = pushCase(git, root, steps, userCase, operator, userCredential);
        ops("contents_write_push", userPush.outcome().name(), userPush.detail());
        Optional<GitCredential> appCredential = tokens.token(settings.installationId(), settings.repository(),
                CONTENTS_WRITE).value().map(t -> new GitCredential("x-access-token", t.token().toCharArray()));
        var agent = new Identity(settings.slug() + "[bot]", settings.appId() + "+" + settings.slug()
                + "[bot]@users.noreply.github.com");
        GitResult<?> appPush = pushCase(git, root, steps, appCase, agent, appCredential);
        appCase.values.put("app_push", appPush.outcome().name() + " " + appPush.detail());
        return userPush.isOk();
    }

    private GitResult<?> pushCase(JGitOperations git, Path root, Map<String, Object> steps, Case c, Identity identity,
            Optional<GitCredential> credential) throws IOException {
        Path worktree = root.resolve("wt").resolve(c.name);
        var added = timed(steps, c.name + "_worktree_add", () -> git.worktreeAdd(new WorktreeAddInput(root, worktree,
                Optional.of(c.branch), true, "refs/remotes/origin/main")));
        if (!added.isOk()) {
            return added;
        }
        Files.createDirectories(worktree.resolve(c.file).getParent());
        Files.writeString(worktree.resolve(c.file), "fixture run " + c.branch + "\n", StandardCharsets.UTF_8);
        var commit = timed(steps, c.name + "_commit", () -> git.commit(new CommitInput(worktree,
                "test: fixture run " + c.branch, identity, identity)));
        if (!commit.isOk()) {
            return commit;
        }
        var pushed = timed(steps, c.name + "_push", () -> git.push(new PushInput(worktree, "origin", "HEAD",
                "refs/heads/" + c.branch, Optional.empty(), credential)));
        if (pushed.isOk()) {
            c.headSha = commit.value().map(v -> v.id()).orElse(null);
            c.values.put("branch", c.branch);
            c.values.put(HEAD_SHA, c.headSha);
            c.values.put("commit_author", identity.name());
        }
        return pushed;
    }

    private <T> GitResult<T> timed(Map<String, Object> steps, String name, Supplier<GitResult<T>> step) {
        long start = System.nanoTime();
        GitResult<T> result = step.get();
        var entry = new LinkedHashMap<String, Object>();
        entry.put(OUTCOME, result.outcome().name());
        entry.put("ms", (System.nanoTime() - start) / 1_000_000L);
        if (!result.detail().isEmpty()) {
            entry.put(DETAIL, results.redact(result.detail()));
        }
        steps.put(name, entry);
        return result;
    }

    // --- pull requests ------------------------------------------------------------------------------------------

    private void openPullRequests() {
        CiResponse created = openPullRequest(userCase, user);
        ops("pr_create", created);
        if (appCase.headSha != null) {
            installationClient(PR_WRITE).ifPresent(app -> {
                CiResponse response = openPullRequest(appCase, app);
                appCase.values.put("app_pr_create", response.outcome().name() + " " + response.status());
            });
        }
    }

    private CiResponse openPullRequest(Case c, CiHttpClient client) {
        var body = new LinkedHashMap<String, Object>();
        body.put("title", "Fixture run " + c.branch);
        body.put("head", c.branch);
        body.put("base", "main");
        body.put("body", "Automated verification run of plan-marshall-mcp (M17-M19, user token, case " + c.name
                + "). Closed by the run.");
        CiResponse created = client.send("POST", repoPath + "/pulls", Optional.of(Json.write(body)), Optional.empty());
        if (!created.isOk()) {
            c.values.put("pr_create_error", results.redact(created.detail() + " " + created.body()));
            return created;
        }
        Object pr = Json.parse(created.body());
        c.number = Integer.parseInt(Json.string(pr, "number").orElseThrow());
        c.nodeId = Json.string(pr, "node_id").orElseThrow();
        c.values.put("pr_url", Json.string(pr, "html_url").orElse(""));
        c.values.put("pr_author", Json.string(pr, "user", LOGIN).orElse(""));
        c.values.put("pr_author_type", Json.string(pr, "user", "type").orElse(""));
        c.values.put("pr_created_at", Json.string(pr, CREATED_AT).orElse(""));
        CiResponse issue = user.get(repoPath + "/issues/" + c.number);
        c.values.put("performed_via_github_app", Json.string(Json.parseOrNull(issue.body()),
                "performed_via_github_app", "slug").orElse("(none)"));
        return created;
    }

    private void userOperations() {
        if (userCase.number == 0) {
            return;
        }
        var body = new LinkedHashMap<String, Object>();
        String text = "Fixture review thread " + userCase.branch;
        body.put("body", text);
        body.put("commit_id", userCase.headSha);
        body.put("path", userCase.file);
        body.put("line", 1);
        body.put("side", "RIGHT");
        ops("review_comment_create", user.send("POST", repoPath + "/pulls/" + userCase.number + "/comments",
                Optional.of(Json.write(body)), Optional.empty()));
        var github = new GitHubClient(user, GitHubClient.GRAPHQL_PATH);
        var threads = github.reviewThreads(settings.owner(), settings.repository(), userCase.number);
        ops("review_threads_list", threads.outcome().name(),
                threads.detail() + threads.value().map(t -> " threads=" + t.size()).orElse(""));
        Optional<GitHubClient.ReviewThread> own = threads.value().orElse(List.of()).stream()
                .filter(t -> t.comments().stream().anyMatch(c -> text.equals(c.body()))).findFirst();
        if (own.isPresent()) {
            CiResult<Boolean> resolved = github.resolveReviewThread(own.get().id());
            ops("review_thread_resolve", resolved.outcome().name(),
                    resolved.detail() + resolved.value().map(r -> " isResolved=" + r).orElse(""));
        } else {
            ops("review_thread_resolve", "SKIPPED", "own thread not found");
        }
        CiResult<GitHubClient.MergeQueueEntry> entry = github.enqueue(userCase.nodeId, userCase.headSha);
        ops("merge_queue_enqueue", entry.outcome().name(),
                entry.detail() + entry.value().map(e -> " state=" + e.state()).orElse(""));
        // beyond the App permissions: the operator may read webhooks, the App has no webhook permission
        ops("probe_hooks_read_beyond_app", user.get(repoPath + "/hooks"));
    }

    // --- review bots --------------------------------------------------------------------------------------------

    private void waitForAutoReviews() {
        if (userCase.number == 0) {
            return;
        }
        Duration bound = Duration.ofMinutes(Long.getLong("spike.botWaitMinutes", 15));
        Instant start = Instant.now();
        Instant deadline = start.plus(bound);
        while (true) {
            poll();
            var activity = userCase.activity;
            boolean done = BOTS.stream().allMatch(b -> !activity.get(b).isEmpty())
                    && activity.get(CODERABBIT).stream().anyMatch(e -> "review".equals(e.get("kind"))
                    || SKIP.matcher(String.valueOf(e.get("excerpt"))).find());
            if (done || Instant.now().isAfter(deadline)) {
                break;
            }
            sleep(POLL);
        }
        m17.put("auto_review_waited_seconds", Duration.between(start, Instant.now()).toSeconds());
        m17.put("auto_review_wait_bound_minutes", bound.toMinutes());
    }

    private void triggers() {
        for (Case c : List.of(userCase, appCase)) {
            if (c.number == 0) {
                continue;
            }
            CiResult<String> comment = new GitHubClient(user, GitHubClient.GRAPHQL_PATH).postComment(settings.owner(),
                    settings.repository(), c.number, TRIGGER);
            if (c == userCase) {
                ops("comment_create", comment.outcome().name(), comment.detail());
            }
            if (!comment.isOk()) {
                c.values.put("trigger_error", results.redact(comment.outcome().name() + " " + comment.detail()));
                continue;
            }
            c.triggerId = comment.value().orElseThrow();
            Object json = Json.parseOrNull(user.get(repoPath + "/issues/comments/" + c.triggerId).body());
            c.triggerAt = Json.string(json, CREATED_AT).map(Instant::parse).orElse(Instant.now());
            var trigger = new LinkedHashMap<String, Object>();
            trigger.put("id", c.triggerId);
            trigger.put("body", TRIGGER);
            trigger.put("author", Json.string(json, "user", LOGIN).orElse(""));
            trigger.put("author_type", Json.string(json, "user", "type").orElse(""));
            trigger.put("performed_via_github_app", Json.string(json, "performed_via_github_app", "slug")
                    .orElse("(none)"));
            trigger.put(CREATED_AT, c.triggerAt.toString());
            trigger.put("url", Json.string(json, "html_url").orElse(""));
            c.values.put("trigger_comment", trigger);
        }
    }

    private void waitForAnswers() {
        Duration bound = Duration.ofMinutes(Long.getLong("spike.triggerWaitMinutes", 10));
        Instant start = Instant.now();
        Instant deadline = start.plus(bound);
        List<Map<String, Object>> runs;
        while (true) {
            poll();
            runs = runs();
            List<Map<String, Object>> polledRuns = runs;
            boolean answered = List.of(userCase, appCase).stream().filter(c -> c.triggerAt != null)
                    .allMatch(c -> !afterTrigger(c, c.activity.get(CODERABBIT)).isEmpty());
            boolean ciDone = polledRuns.stream().anyMatch(r -> "push".equals(r.get("event")))
                    && polledRuns.stream().anyMatch(r -> "pull_request".equals(r.get("event")))
                    && polledRuns.stream().allMatch(r -> "completed".equals(r.get(STATUS)));
            if ((answered && ciDone) || Instant.now().isAfter(deadline)) {
                break;
            }
            sleep(POLL);
        }
        // a late edit or reply may follow the first answer: one more look after a short grace period
        sleep(POLL);
        poll();
        m17.put("trigger_waited_seconds", Duration.between(start, Instant.now()).toSeconds());
        m17.put("trigger_wait_bound_minutes", bound.toMinutes());
        for (Case c : List.of(userCase, appCase)) {
            if (c.number > 0) {
                c.values.put("bots", summary(c));
                c.values.put("other_bots_seen", List.copyOf(otherBots));
                if (c.triggerId != null) {
                    c.values.put("trigger_reactions", Json.list(Json.parseOrNull(user.get(repoPath
                            + "/issues/comments/" + c.triggerId + "/reactions").body())).stream()
                            .map(r -> Json.string(r, "user", LOGIN).orElse("") + " " + Json.string(r, "content")
                                    .orElse("")).toList());
                }
            }
        }
        m18.put("pr_url", userCase.values.get("pr_url"));
        m18.put(HEAD_SHA, userCase.headSha);
        m18.put("runs", runs);
        ops("actions_read", runs.isEmpty() ? "EMPTY" : "OK", "runs=" + runs.size());
    }

    private void poll() {
        for (Case c : List.of(userCase, appCase)) {
            if (c.number > 0) {
                c.activity = botActivity(c.number);
            }
        }
    }

    private Map<String, Object> summary(Case c) {
        var perBot = new LinkedHashMap<String, Object>();
        for (String bot : BOTS) {
            List<Map<String, Object>> events = c.activity.get(bot);
            List<Map<String, Object>> before = events.stream().filter(e -> afterTrigger(c, List.of(e)).isEmpty())
                    .toList();
            List<Map<String, Object>> after = afterTrigger(c, events);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("before_trigger", before.isEmpty() ? SILENT
                    : before.stream().allMatch(e -> SKIP.matcher(String.valueOf(e.get("excerpt"))).find())
                    ? "skip_notice_only" : "reviewed_or_commented");
            entry.put("after_trigger", after.isEmpty() ? SILENT
                    : after.stream().anyMatch(e -> ANSWER.matcher(String.valueOf(e.get("excerpt"))).find())
                    ? "answered_review_triggered"
                    : after.stream().anyMatch(e -> SKIP.matcher(String.valueOf(e.get("excerpt"))).find())
                    ? "answered_skip" : "other_activity");
            entry.put(EVENTS, events.stream().map(e -> {
                var copy = new LinkedHashMap<>(e);
                copy.put("after_trigger", !afterTrigger(c, List.of(e)).isEmpty());
                return copy;
            }).toList());
            perBot.put(bot, entry);
        }
        return perBot;
    }

    private static List<Map<String, Object>> afterTrigger(Case c, List<Map<String, Object>> events) {
        if (c.triggerAt == null) {
            return List.of();
        }
        return events.stream().filter(e -> !Instant.parse(String.valueOf(e.get(CREATED_AT))).isBefore(c.triggerAt))
                .toList();
    }

    private Map<String, List<Map<String, Object>>> botActivity(int number) {
        var activity = new LinkedHashMap<String, List<Map<String, Object>>>();
        BOTS.forEach(b -> activity.put(b, new ArrayList<>()));
        collect("/issues/" + number + "/comments?per_page=100", "issue_comment", CREATED_AT, activity);
        collect("/pulls/" + number + "/reviews?per_page=100", "review", "submitted_at", activity);
        collect("/pulls/" + number + "/comments?per_page=100", "review_comment", CREATED_AT, activity);
        return activity;
    }

    private void collect(String path, String kind, String timeField, Map<String, List<Map<String, Object>>> activity) {
        for (String page : user.getAll(repoPath + path, 10).pages()) {
            for (Object item : Json.list(Json.parseOrNull(page))) {
                String author = Json.string(item, "user", LOGIN).orElse("");
                if (author.endsWith("[bot]") && !activity.containsKey(author)
                        && !author.equals(settings.slug() + "[bot]")) {
                    otherBots.add(author);
                }
                if (activity.containsKey(author)) {
                    var event = new LinkedHashMap<String, Object>();
                    event.put("kind", kind);
                    event.put("id", Json.string(item, "id").orElse(""));
                    event.put(CREATED_AT, Json.string(item, timeField).orElse(Instant.EPOCH.toString()));
                    Json.string(item, "updated_at").ifPresent(u -> event.put("updated_at", u));
                    Json.string(item, "state").ifPresent(s -> event.put("state", s));
                    String body = Json.string(item, "body").orElse("");
                    event.put("excerpt", body.length() > EXCERPT ? body.substring(0, EXCERPT) : body);
                    activity.get(author).add(event);
                }
            }
        }
    }

    // --- CI, checks and statuses --------------------------------------------------------------------------------

    private List<Map<String, Object>> runs() {
        var runs = new ArrayList<Map<String, Object>>();
        if (userCase.headSha == null) {
            return runs;
        }
        Object json = Json.parseOrNull(user.get(repoPath + "/actions/runs?head_sha=" + userCase.headSha
                + "&per_page=50").body());
        for (Object run : Json.list(json, "workflow_runs")) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", Json.string(run, "id").orElse(""));
            entry.put("name", Json.string(run, "name").orElse(""));
            entry.put("event", Json.string(run, "event").orElse(""));
            entry.put(HEAD_SHA, Json.string(run, HEAD_SHA).orElse(""));
            entry.put(STATUS, Json.string(run, STATUS).orElse(""));
            entry.put("conclusion", Json.string(run, "conclusion").orElse(""));
            entry.put("actor", Json.string(run, "actor", LOGIN).orElse(""));
            entry.put("triggering_actor", Json.string(run, "triggering_actor", LOGIN).orElse(""));
            entry.put("url", Json.string(run, "html_url").orElse(""));
            runs.add(entry);
        }
        return runs;
    }

    private void checksAndStatuses() {
        if (userCase.headSha == null) {
            return;
        }
        CiResponse checks = user.get(repoPath + "/commits/" + userCase.headSha + "/check-runs");
        ops("checks_read", checks.outcome().name(), checks.isOk()
                ? "check_runs=" + Json.string(Json.parseOrNull(checks.body()), "total_count").orElse("?")
                : checks.detail() + " " + checks.body());
        CiResponse statuses = user.get(repoPath + "/commits/" + userCase.headSha + "/status");
        ops("statuses_read", statuses.outcome().name(), statuses.isOk()
                ? "state=" + Json.string(Json.parseOrNull(statuses.body()), "state").orElse("?")
                : statuses.detail() + " " + statuses.body());
    }

    // --- cleanup and refresh ------------------------------------------------------------------------------------

    private void cleanup() {
        var cleanup = new LinkedHashMap<String, Object>();
        if (userCase.number > 0) {
            CiResponse closed = user.send("PATCH", repoPath + "/pulls/" + userCase.number,
                    Optional.of(Json.write(Map.of("state", "closed"))), Optional.empty());
            ops("pr_close", closed);
        }
        if (userCase.headSha != null) {
            ops("branch_delete", user.send("DELETE", repoPath + "/git/refs/heads/" + userCase.branch,
                    Optional.empty(), Optional.empty()));
        }
        if (appCase.number > 0) {
            installationClient(PR_WRITE).ifPresent(c -> {
                CiResponse closed = c.send("PATCH", repoPath + "/pulls/" + appCase.number,
                        Optional.of(Json.write(Map.of("state", "closed"))), Optional.empty());
                cleanup.put("hybrid_pr_close", closed.outcome().name() + " " + closed.status());
            });
        }
        if (appCase.headSha != null) {
            installationClient(CONTENTS_WRITE).ifPresent(c -> {
                CiResponse deleted = c.send("DELETE", repoPath + "/git/refs/heads/" + appCase.branch, Optional.empty(),
                        Optional.empty());
                cleanup.put("hybrid_branch_delete", deleted.outcome().name() + " " + deleted.status());
            });
        }
        m19.put("cleanup", cleanup);
    }

    private void refresh() throws IOException {
        var refresh = new LinkedHashMap<String, Object>();
        m19.put("refresh", refresh);
        refresh.put("client_secret_sent", false);
        try (var oauth = new CiHttpClient(CiEndpoint.of(WEB), Optional::empty, Map.of("Accept", "application/json"))) {
            var body = new LinkedHashMap<String, Object>();
            body.put("client_id", settings.clientId());
            body.put("grant_type", REFRESH_TOKEN);
            body.put(REFRESH_TOKEN, refreshToken);
            CiResponse response = oauth.send("POST", "login/oauth/access_token", Optional.of(Json.write(body)),
                    Optional.empty());
            Object json = Json.parseOrNull(response.body());
            refresh.put(STATUS, response.status());
            Optional<String> error = Json.string(json, "error");
            Optional<String> access = Json.string(json, ACCESS_TOKEN);
            Optional<String> renewed = Json.string(json, REFRESH_TOKEN);
            if (error.isPresent() || access.isEmpty() || renewed.isEmpty()) {
                refresh.put("works", false);
                refresh.put("error", error.orElse("no token in response"));
                refresh.put("error_description", Json.string(json, "error_description").orElse(""));
                refresh.put("client_secret_required", "incorrect_client_credentials".equals(error.orElse("")));
                return;
            }
            results.secret(access.get());
            results.secret(renewed.get());
            long now = Instant.now().getEpochSecond();
            var stored = new LinkedHashMap<String, Object>();
            stored.put(ACCESS_TOKEN, access.get());
            Json.at(json, EXPIRES_IN).ifPresent(v -> stored.put(EXPIRES_IN, v));
            stored.put(REFRESH_TOKEN, renewed.get());
            Json.at(json, REFRESH_EXPIRES_IN).ifPresent(v -> stored.put(REFRESH_EXPIRES_IN, v));
            Json.at(json, "scope").ifPresent(v -> stored.put("scope", v));
            Json.at(json, "token_type").ifPresent(v -> stored.put("token_type", v));
            stored.put("obtained_at", now);
            writeTokenFile(Json.write(stored));
            refresh.put("works", true);
            refresh.put("client_secret_required", false);
            refresh.put("access_token_prefix", access.get().substring(0, Math.min(4, access.get().length())));
            refresh.put("access_token_rotated", !access.get().equals(userToken));
            refresh.put("refresh_token_rotated", !renewed.get().equals(refreshToken));
            Json.string(json, EXPIRES_IN).ifPresent(v -> {
                refresh.put(EXPIRES_IN, Long.parseLong(v));
                refresh.put("access_expires_at", Instant.ofEpochSecond(now + Long.parseLong(v)).toString());
            });
            Json.string(json, REFRESH_EXPIRES_IN).ifPresent(v -> {
                refresh.put(REFRESH_EXPIRES_IN, Long.parseLong(v));
                refresh.put("refresh_expires_at", Instant.ofEpochSecond(now + Long.parseLong(v)).toString());
            });
            refresh.put("token_file_mode", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile)));
            String fresh = access.get();
            try (var renewedClient = new CiHttpClient(CiEndpoint.of(API), () -> Optional.of(fresh), HEADERS)) {
                CiResponse me = renewedClient.get("user");
                refresh.put("new_token_user_get", me.status() + " " + Json.string(Json.parseOrNull(me.body()), LOGIN)
                        .orElse(""));
            }
            CiResponse old = user.get("user");
            refresh.put("old_access_token_after_refresh", old.status());
        }
    }

    private void writeTokenFile(String content) throws IOException {
        Path next = tokenFile.resolveSibling(tokenFile.getFileName() + ".next");
        Files.deleteIfExists(next);
        Files.createFile(next, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(next, content + "\n", StandardCharsets.UTF_8);
        Files.move(next, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // --- evaluation and helpers ---------------------------------------------------------------------------------

    private static boolean evaluateM17(Case c) {
        if (!(c.values.get("bots") instanceof Map<?, ?> bots) || !(bots.get(CODERABBIT) instanceof Map<?, ?> bot)) {
            return false;
        }
        return "reviewed_or_commented".equals(bot.get("before_trigger"))
                || "answered_review_triggered".equals(bot.get("after_trigger"));
    }

    private boolean evaluateM18() {
        if (!(m18.get("runs") instanceof List<?> runs)) {
            return false;
        }
        boolean push = runs.stream().anyMatch(r -> r instanceof Map<?, ?> m && "push".equals(m.get("event")));
        boolean pullRequest = runs.stream().anyMatch(r -> r instanceof Map<?, ?> m
                && "pull_request".equals(m.get("event")));
        m18.put("push_run", push);
        m18.put("pull_request_run", pullRequest);
        return push && pullRequest;
    }

    private boolean evaluateM19() {
        m19.put("operations", operations);
        List<String> required = List.of("user_get", "contents_read_fetch", "contents_write_push", "pr_create",
                "comment_create", "review_comment_create", "review_threads_list", "review_thread_resolve",
                "checks_read", "statuses_read", "actions_read", "pr_close", "branch_delete");
        boolean ok = required.stream().allMatch(o -> operations.get(o) instanceof Map<?, ?> m
                && "OK".equals(String.valueOf(m.get(OUTCOME))));
        return ok && m19.get("refresh") instanceof Map<?, ?> r && Boolean.TRUE.equals(r.get("works"));
    }

    private Optional<CiHttpClient> installationClient(Map<String, String> permissions) {
        return tokens.token(settings.installationId(), settings.repository(), permissions).value()
                .map(t -> track(new CiHttpClient(CiEndpoint.of(API), () -> Optional.of(t.token()), HEADERS)));
    }

    private CiHttpClient track(CiHttpClient client) {
        clients.add(client);
        return client;
    }

    private void ops(String operation, CiResponse response) {
        ops(operation, response.outcome().name(), response.isOk() ? "HTTP " + response.status()
                : response.detail() + " " + response.body());
    }

    private void ops(String operation, String outcome, String detail) {
        var entry = new LinkedHashMap<String, Object>();
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
