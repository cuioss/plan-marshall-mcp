/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.cuioss.pm.provider.ci.CiEndpoint;
import de.cuioss.pm.provider.ci.CiHttpClient;
import de.cuioss.pm.provider.ci.CiResult;
import de.cuioss.pm.provider.ci.Json;
import de.cuioss.pm.provider.github.FakeServer.Response;
import de.cuioss.pm.provider.github.GitHubClient.ReviewThread;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GitHubClient")
class GitHubClientTest {

    private FakeServer server;
    private CiHttpClient http;
    private GitHubClient client;

    @BeforeEach
    void start() {
        server = new FakeServer();
        http = new CiHttpClient(CiEndpoint.of(server.base()), () -> Optional.of("ghs_installation"),
                InstallationTokens.HEADERS);
        client = new GitHubClient(http, GitHubClient.GRAPHQL_PATH);
    }

    @AfterEach
    void stop() {
        http.close();
        server.close();
    }

    private static String page(String threads, boolean hasNext, String cursor) {
        return "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{\"pageInfo\":{\"hasNextPage\":" + hasNext
                + ",\"endCursor\":" + (cursor == null ? "null" : "\"" + cursor + "\"") + "},\"nodes\":[" + threads
                + "]}}}}}";
    }

    private static String thread(String id, boolean resolved) {
        return "{\"id\":\"" + id + "\",\"isResolved\":" + resolved + ",\"isOutdated\":false,\"path\":\"src/A.java\","
                + "\"line\":12,\"comments\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\":[{\"id\":\"C1\","
                + "\"databaseId\":101,\"author\":{\"login\":\"coderabbitai[bot]\"},\"body\":\"Consider\\nthis\","
                + "\"createdAt\":\"2026-10-05T10:00:00Z\"}]}}";
    }

    private Object requestJson(int index) {
        return Json.parse(server.requests().get(index).body());
    }

    @Nested
    @DisplayName("review threads")
    class ReviewThreads {

        @Test
        @DisplayName("lists every page with the fixed document and the cursor")
        void listsPages() {
            server.on("POST", "/graphql", Response.json(200, page(thread("T1", false), true, "CUR1")));
            server.on("POST", "/graphql", Response.json(200, page(thread("T2", true), false, null)));

            CiResult<List<ReviewThread>> result = client.reviewThreads("cuioss", "plan-marshall-mcp", 7);

            assertTrue(result.isOk());
            assertTrue(result.complete());
            List<ReviewThread> threads = result.value().orElseThrow();
            assertEquals(List.of("T1", "T2"), threads.stream().map(ReviewThread::id).toList());
            ReviewThread first = threads.getFirst();
            assertFalse(first.resolved());
            assertEquals(Optional.of("12"), first.line());
            assertEquals("Consider\nthis", first.comments().getFirst().body());
            assertEquals(Optional.of("coderabbitai[bot]"), first.comments().getFirst().author());
            assertTrue(first.complete());
            assertEquals(Optional.of(GitHubClient.REVIEW_THREADS), Json.string(requestJson(0), "query"));
            assertTrue(Json.at(requestJson(0), "variables", "cursor").isEmpty());
            assertEquals(Optional.of("CUR1"), Json.string(requestJson(1), "variables", "cursor"));
            assertEquals(Optional.of("7"), Json.string(requestJson(1), "variables", "number"));
            assertEquals("Bearer ghs_installation", server.requests().getFirst().header("Authorization"));
        }

        @Test
        @DisplayName("reports a missing pull request and GraphQL errors")
        void missing() {
            server.on("POST", "/graphql", Response.json(200, "{\"data\":{\"repository\":{\"pullRequest\":null}}}"));
            server.on("POST", "/graphql",
                    Response.json(200, "{\"data\":null,\"errors\":[{\"type\":\"NOT_FOUND\",\"message\":\"no repo\"}]}"));

            assertEquals(CiResult.Outcome.NOT_FOUND, client.reviewThreads("o", "r", 1).outcome());
            var error = client.reviewThreads("o", "missing", 1);
            assertEquals(CiResult.Outcome.NOT_FOUND, error.outcome());
            assertEquals("no repo", error.detail());
        }

        @Test
        @DisplayName("resolves a thread")
        void resolves() {
            server.on("POST", "/graphql",
                    Response.json(200, "{\"data\":{\"resolveReviewThread\":{\"thread\":{\"id\":\"T1\",\"isResolved\":true}}}}"));

            CiResult<Boolean> result = client.resolveReviewThread("T1");

            assertEquals(Optional.of(true), result.value());
            assertEquals(Optional.of(GitHubClient.RESOLVE_REVIEW_THREAD), Json.string(requestJson(0), "query"));
            assertEquals(Optional.of("T1"), Json.string(requestJson(0), "variables", "threadId"));
        }

        @Test
        @DisplayName("passes a rate limit through with its reset instant")
        void rateLimited() {
            server.on("POST", "/graphql", new Response(403, "{\"message\":\"API rate limit exceeded\"}",
                    Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1791194400")));

            var result = client.resolveReviewThread("T1");

            assertEquals(CiResult.Outcome.RATE_LIMITED, result.outcome());
            assertEquals(1791194400L, result.resetAt().orElseThrow().getEpochSecond());
        }
    }

    @Nested
    @DisplayName("merge queue and comments")
    class MergeQueueAndComments {

        @Test
        @DisplayName("enqueues bound to the expected head")
        void enqueues() {
            server.on("POST", "/graphql", Response.json(200, "{\"data\":{\"enqueuePullRequest\":{\"mergeQueueEntry\":"
                    + "{\"id\":\"MQE1\",\"position\":3,\"state\":\"QUEUED\"}}}}"));

            var entry = client.enqueue("PR_node", "abc123").value().orElseThrow();

            assertEquals(new GitHubClient.MergeQueueEntry("MQE1", "3", "QUEUED"), entry);
            assertEquals(Optional.of(GitHubClient.ENQUEUE_PULL_REQUEST), Json.string(requestJson(0), "query"));
            assertEquals(Optional.of("abc123"), Json.string(requestJson(0), "variables", "expectedHeadOid"));
            assertEquals(Optional.of("PR_node"), Json.string(requestJson(0), "variables", "pullRequestId"));
        }

        @Test
        @DisplayName("reports a refused enqueue and an empty answer")
        void refusedEnqueue() {
            server.on("POST", "/graphql", Response.json(200,
                    "{\"errors\":[{\"type\":\"UNPROCESSABLE\",\"message\":\"Expected head oid does not match\"}]}"));
            server.on("POST", "/graphql", Response.json(200, "{\"data\":{\"enqueuePullRequest\":null}}"));
            server.on("POST", "/graphql", Response.json(200, "{}"));

            var refused = client.enqueue("PR_node", "stale");

            assertEquals(CiResult.Outcome.REJECTED, refused.outcome());
            assertEquals("Expected head oid does not match", refused.detail());
            assertEquals(CiResult.Outcome.FAILED, client.enqueue("PR_node", "x").outcome());
            assertEquals(CiResult.Outcome.FAILED, client.enqueue("PR_node", "x").outcome());
        }

        @Test
        @DisplayName("posts a multi-line comment through REST")
        void postsComment() {
            server.on("POST", "/repos/cuioss/pm/issues/5/comments", Response.json(201, "{\"id\":9001}"));
            String body = "## Gaps\n\n- one \"quoted\"\n";

            var result = client.postComment("cuioss", "pm", 5, body);

            assertEquals(Optional.of("9001"), result.value());
            assertEquals(Optional.of(body), Json.string(requestJson(0), "body"));
        }

        @Test
        @DisplayName("maps refused comment posts")
        void refusedComment() {
            server.on("POST", "/repos/cuioss/pm/issues/6/comments", Response.json(201, "{}"));

            assertEquals(CiResult.Outcome.NOT_FOUND, client.postComment("cuioss", "pm", 404, "x").outcome());
            assertEquals(CiResult.Outcome.FAILED, client.postComment("cuioss", "pm", 6, "x").outcome());
        }
    }
}
