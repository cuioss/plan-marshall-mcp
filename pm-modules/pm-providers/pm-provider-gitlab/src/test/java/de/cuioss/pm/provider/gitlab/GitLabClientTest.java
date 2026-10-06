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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import de.cuioss.pm.provider.ci.CiEndpoint;
import de.cuioss.pm.provider.ci.CiHttpClient;
import de.cuioss.pm.provider.ci.CiResult;
import de.cuioss.pm.provider.ci.Json;
import de.cuioss.pm.provider.gitlab.FakeServer.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GitLabClient")
class GitLabClientTest {

    private static final String SELF = "/api/v4/personal_access_tokens/self";
    private static final String MR = "/api/v4/projects/group%2Fproject/merge_requests/12";

    private FakeServer server;
    private CiHttpClient http;
    private GitLabClient client;

    @BeforeEach
    void start() {
        server = new FakeServer();
        http = new CiHttpClient(CiEndpoint.of(URI.create(server.base() + "api/v4")), () -> Optional.of("glpat-secret"),
                Map.of());
        client = new GitLabClient(http, "group/project");
    }

    @AfterEach
    void stop() {
        http.close();
        server.close();
    }

    private Object body(int index) {
        return Json.parse(server.requests().get(index).body());
    }

    @Nested
    @DisplayName("merge path")
    class MergePath {

        @Test
        @DisplayName("merges bound to the expected head")
        void merges() {
            server.on("PUT", MR + "/merge", Response.json(200, "{\"state\":\"merged\",\"merge_commit_sha\":\"m1\"}"));

            CiResult<String> result = client.merge(12, "abc");

            assertEquals(Optional.of("m1"), result.value());
            assertEquals(Optional.of("abc"), Json.string(body(0), "sha"));
            assertEquals("Bearer glpat-secret", server.requests().getFirst().header("Authorization"));
        }

        @Test
        @DisplayName("reports a moved head or an unmergeable request as rejected")
        void rejects() {
            server.on("PUT", MR + "/merge", Response.json(409, "{\"message\":\"SHA does not match HEAD of source branch\"}"));
            server.on("PUT", "/api/v4/projects/group%2Fproject/merge_requests/13/merge", Response.json(200, "{}"));

            var moved = client.merge(12, "stale");

            assertEquals(CiResult.Outcome.REJECTED, moved.outcome());
            assertTrue(moved.detail().contains("SHA does not match"));
            assertEquals(CiResult.Outcome.FAILED, client.merge(13, "x").outcome());
        }

        @Test
        @DisplayName("reports a merge train that is not enabled as rejected, not as failed")
        void mergeTrainNotEnabled() {
            server.on("POST", "/api/v4/projects/group%2Fproject/merge_trains/merge_requests/12",
                    Response.json(400, "{\"message\":\"Merge trains are not enabled for this project\"}"));
            server.on("POST", "/api/v4/projects/group%2Fproject/merge_trains/merge_requests/13",
                    Response.json(502, "<html>Bad Gateway</html>"));

            var refused = client.mergeTrainAdd(12, "abc");

            assertEquals(CiResult.Outcome.REJECTED, refused.outcome());
            assertEquals("HTTP 400: Merge trains are not enabled for this project", refused.detail());
            assertEquals(CiResult.Outcome.FAILED, client.mergeTrainAdd(13, "abc").outcome());
        }

        @Test
        @DisplayName("adds to the merge train bound to the expected head")
        void mergeTrain() {
            server.on("POST", "/api/v4/projects/group%2Fproject/merge_trains/merge_requests/12",
                    Response.json(201, "[{\"id\":1,\"status\":\"idle\"}]"));

            var result = client.mergeTrainAdd(12, "abc");

            assertTrue(result.isOk());
            assertEquals(Optional.of("abc"), Json.string(body(0), "sha"));
            assertEquals(CiResult.Outcome.NOT_FOUND, client.mergeTrainAdd(99, "abc").outcome());
        }
    }

    @Nested
    @DisplayName("discussions")
    class Discussions {

        private static final String NOTE = "{\"id\":%d,\"author\":{\"username\":\"%s\"},\"body\":\"%s\","
                + "\"resolvable\":true,\"resolved\":%s}";

        private String discussion(String id, String author, boolean resolved) {
            return "{\"id\":\"" + id + "\",\"notes\":[" + NOTE.formatted(1, author, "Fix\\nthis", resolved) + "]}";
        }

        @Test
        @DisplayName("lists every page of discussions")
        void lists() {
            server.on("GET", MR + "/discussions?per_page=100", new Response(200, "[" + discussion("d1", "bot", false) + "]",
                    Map.of("Link", "<" + server.base() + "api/v4/projects/group%2Fproject/merge_requests/12/discussions"
                            + "?per_page=100&page=2>; rel=\"next\"")));
            server.on("GET", MR + "/discussions?per_page=100&page=2",
                    Response.json(200, "[" + discussion("d2", "dev", true) + "]"));

            var result = client.discussions(12);

            assertTrue(result.complete());
            var discussions = result.value().orElseThrow();
            assertEquals(List.of("d1", "d2"), discussions.stream().map(GitLabClient.Discussion::id).toList());
            assertFalse(discussions.getFirst().resolved());
            assertTrue(discussions.get(1).resolved());
            assertEquals("Fix\nthis", discussions.getFirst().notes().getFirst().body());
            assertEquals("bot", discussions.getFirst().notes().getFirst().author());
        }

        @Test
        @DisplayName("reports a failed first page")
        void failedFirstPage() {
            assertEquals(CiResult.Outcome.NOT_FOUND, client.discussions(404).outcome());
        }

        @Test
        @DisplayName("replies with the body in memory and resolves")
        void repliesAndResolves() {
            server.on("POST", MR + "/discussions/d1/notes", Response.json(201, "{\"id\":77}"));
            server.on("PUT", MR + "/discussions/d1?resolved=true", Response.json(200, discussion("d1", "bot", true)));
            String reply = "Fixed in abc.\n\n> quoted \"text\"";

            var replied = client.reply(12, "d1", reply);
            var resolved = client.resolve(12, "d1");

            assertEquals(Optional.of("77"), replied.value());
            assertEquals(Optional.of(reply), Json.string(body(0), "body"));
            assertEquals(Optional.of(true), resolved.value());
        }

        @Test
        @DisplayName("maps failures of reply and resolve")
        void replyFailures() {
            server.on("POST", MR + "/discussions/d9/notes", Response.json(201, "{}"));

            assertEquals(CiResult.Outcome.FAILED, client.reply(12, "d9", "x").outcome());
            assertEquals(CiResult.Outcome.NOT_FOUND, client.reply(12, "zz", "x").outcome());
            assertEquals(CiResult.Outcome.NOT_FOUND, client.resolve(12, "zz").outcome());
        }
    }

    @Nested
    @DisplayName("job trace and token identity")
    class TraceAndIdentity {

        @Test
        @DisplayName("reads a job trace")
        void trace() {
            server.on("GET", "/api/v4/projects/group%2Fproject/jobs/5/trace",
                    new Response(200, "line 1\nline 2\n", Map.of("Content-Type", "text/plain")));

            assertEquals(Optional.of("line 1\nline 2\n"), client.jobTrace(5).value());
            assertEquals(CiResult.Outcome.NOT_FOUND, client.jobTrace(6).outcome());
        }

        @Test
        @DisplayName("reads the token identity")
        void identity() {
            server.on("GET", SELF, Response.json(200, "{\"id\":4,\"name\":\"pm-mcp\","
                    + "\"user_id\":17,\"scopes\":[\"api\",\"read_repository\"],\"active\":true,\"expires_at\":null}"));

            var identity = client.tokenIdentity().value().orElseThrow();

            assertEquals("17", identity.userId());
            assertEquals(List.of("api", "read_repository"), identity.scopes());
            assertTrue(identity.active());
            assertTrue(identity.expiresAt().isEmpty());
            assertEquals("pm-mcp", identity.name());
        }

        @Test
        @DisplayName("resolves the identity of a token that is no personal access token through GET /user")
        void identityOfOAuthToken() {
            server.on("GET", SELF, Response.json(400,
                    "{\"message\":\"400 Bad request - requires token type to be a personal access token\"}"));
            server.on("GET", "/api/v4/user", Response.json(200,
                    "{\"id\":17,\"username\":\"oliver\",\"state\":\"active\"}"));

            var result = client.tokenIdentity();

            var identity = result.value().orElseThrow();
            assertEquals(CiResult.Outcome.OK, result.outcome());
            assertEquals("17", identity.userId());
            assertTrue(identity.active());
            assertEquals("", identity.id());
            assertEquals("", identity.name());
            assertEquals(List.of(), identity.scopes());
            assertTrue(identity.expiresAt().isEmpty());
            assertEquals(List.of("GET " + SELF, "GET /api/v4/user"), server.requests().stream()
                    .map(r -> r.method() + " " + r.pathAndQuery()).toList());
            assertEquals("Bearer glpat-secret", server.requests().getLast().header("Authorization"));
        }

        @Test
        @DisplayName("reports a blocked user and the failures of GET /user for a token of another type")
        void identityFallbackFailures() {
            server.on("GET", SELF, Response.json(400,
                    "{\"message\":\"400 Bad request - requires token type to be a personal access token\"}"));
            server.on("GET", "/api/v4/user", Response.json(200, "{\"id\":18,\"state\":\"blocked\"}"));
            server.on("GET", "/api/v4/user", Response.json(200, "{}"));
            server.on("GET", "/api/v4/user", Response.json(401, "{\"message\":\"401 Unauthorized\"}"));

            var blocked = client.tokenIdentity();
            var unreadable = client.tokenIdentity();
            var refused = client.tokenIdentity();

            assertFalse(blocked.value().orElseThrow().active());
            assertEquals(CiResult.Outcome.FAILED, unreadable.outcome());
            assertEquals(CiResult.Outcome.AUTH_FAILED, refused.outcome());
        }

        @Test
        @DisplayName("does not ask GET /user when the token read fails otherwise")
        void identityWithoutFallback() {
            server.on("GET", SELF, Response.json(500, "{\"message\":\"500 Internal Server Error\"}"));

            assertEquals(CiResult.Outcome.FAILED, client.tokenIdentity().outcome());
            assertEquals(1, server.requests().size());
        }

        @Test
        @DisplayName("reports an invalid token as an authentication failure")
        void unauthorized() {
            server.on("GET", SELF, Response.json(401, "{\"message\":\"401 Unauthorized\"}"));

            assertEquals(CiResult.Outcome.AUTH_FAILED, client.tokenIdentity().outcome());
        }
    }
}
