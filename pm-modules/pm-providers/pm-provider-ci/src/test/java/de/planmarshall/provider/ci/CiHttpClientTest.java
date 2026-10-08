/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.provider.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.planmarshall.provider.ci.CiResponse.Outcome;
import de.planmarshall.provider.ci.FakeServer.Response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CiHttpClient")
class CiHttpClientTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private static final TokenSource TOKEN = () -> Optional.of("s3cret");

    private FakeServer server;
    private CiHttpClient client;

    @BeforeEach
    void start() {
        server = new FakeServer();
        client = new CiHttpClient(new CiEndpoint(server.base(), Set.of()), TOKEN,
                Map.of("Accept", "application/vnd.github+json"), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        client.close();
        server.close();
    }

    @Nested
    @DisplayName("authentication and requests")
    class Requests {

        @Test
        @DisplayName("sends the bearer token and default headers to the origin")
        void bearer() {
            server.on("GET", "/repos/o/r", new Response(200, "{\"id\":1}", Map.of("ETag", "\"v1\"")));

            CiResponse response = client.get("repos/o/r");

            assertEquals(Outcome.OK, response.outcome());
            assertEquals("{\"id\":1}", response.body());
            assertEquals(Optional.of("\"v1\""), response.etag());
            var request = server.requests().getFirst();
            assertEquals("Bearer s3cret", request.header("Authorization"));
            assertEquals("application/vnd.github+json", request.header("Accept"));
        }

        @Test
        @DisplayName("posts a JSON body from memory")
        void postsJson() {
            server.on("POST", "/x", Response.json(201, "{}"));

            CiResponse response = client.send("POST", "/x", Optional.of("{\"body\":\"line1\\nline2\"}"), Optional.empty());

            assertTrue(response.isOk());
            var request = server.requests().getFirst();
            assertEquals("{\"body\":\"line1\\nline2\"}", request.body());
            assertEquals("application/json", request.header("Content-Type"));
        }

        @Test
        @DisplayName("sends no Authorization header without a token")
        void noToken() {
            try (var anonymous = new CiHttpClient(CiEndpoint.of(server.base()), TokenSource.NONE, Map.of())) {
                server.on("GET", "/open", Response.json(200, "[]"));

                assertTrue(anonymous.get("/open").isOk());
                assertNull(server.requests().getFirst().header("Authorization"));
                assertEquals(server.base(), anonymous.endpoint().baseUri());
            }
        }

        @Test
        @DisplayName("refuses a target outside the origin without sending anything")
        void refusesOtherOrigin() {
            CiResponse response = client.get(server.base("localhost") + "steal");

            assertEquals(Outcome.REFUSED, response.outcome());
            assertTrue(server.requests().isEmpty());
        }

        @Test
        @DisplayName("reports an error status and a transport failure")
        void failures() {
            CiResponse notFound = client.get("/missing");
            server.close();
            CiResponse unreachable = client.get("/any");

            assertEquals(Outcome.HTTP_ERROR, notFound.outcome());
            assertEquals(404, notFound.status());
            assertEquals(Outcome.TRANSPORT_FAILED, unreachable.outcome());
            assertEquals(0, unreachable.status());
        }
    }

    @Nested
    @DisplayName("redirects")
    class Redirects {

        @Test
        @DisplayName("drops the token on a redirect to another origin")
        void dropsTokenCrossOrigin() {
            try (var other = new FakeServer(); var redirecting = new CiHttpClient(
                         new CiEndpoint(server.base(), Set.of("localhost")), TOKEN, Map.of())) {
                other.on("GET", "/log", Response.json(200, "log text"));
                server.on("GET", "/jobs/1/logs", new Response(302, "", Map.of("Location", other.base("localhost") + "log")));

                CiResponse response = redirecting.get("/jobs/1/logs");

                assertEquals(Outcome.OK, response.outcome());
                assertEquals("log text", response.body());
                assertEquals("Bearer s3cret", server.requests().getFirst().header("Authorization"));
                assertNull(other.requests().getFirst().header("Authorization"), "token dropped across origins");
            }
        }

        @Test
        @DisplayName("refuses a redirect to a host that is not allowlisted")
        void refusesRedirect() {
            try (var other = new FakeServer()) {
                server.on("GET", "/jobs/2/logs", new Response(302, "", Map.of("Location", other.base("localhost") + "log")));

                CiResponse response = client.get("/jobs/2/logs");

                assertEquals(Outcome.REFUSED, response.outcome());
                assertTrue(response.detail().contains("CROSS_ORIGIN"));
                assertTrue(other.requests().isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("pagination")
    class Pagination {

        private void pages(int count) {
            for (int i = 1; i <= count; i++) {
                var headers = i < count
                        ? Map.of("Link", "<" + server.base() + "items?page=" + (i + 1) + ">; rel=\"next\", <"
                        + server.base() + "items?page=" + count + ">; rel=\"last\"")
                        : Map.<String, String>of();
                server.on("GET", "/items?page=" + i, new Response(200, "[" + i + "]", headers));
            }
        }

        @Test
        @DisplayName("follows every next link and reports completeness")
        void complete() {
            pages(3);

            PagedResult result = client.getAll("items?page=1", 10);

            assertTrue(result.complete());
            assertEquals(List.of("[1]", "[2]", "[3]"), result.pages());
            assertEquals(3, server.requests().size());
            assertTrue(server.requests().stream().allMatch(r -> "Bearer s3cret".equals(r.header("Authorization"))));
        }

        @Test
        @DisplayName("is incomplete at the page cap")
        void pageCap() {
            pages(3);

            PagedResult result = client.getAll("items?page=1", 2);

            assertFalse(result.complete());
            assertEquals(2, result.pages().size());
        }

        @Test
        @DisplayName("is incomplete when a page fails or the next link leaves the origin")
        void incomplete() {
            server.on("GET", "/a?page=1", new Response(200, "[1]", Map.of("Link", "<" + server.base() + "a?page=2>; rel=next")));
            server.on("GET", "/b?page=1",
                    new Response(200, "[1]", Map.of("Link", "<" + server.base("localhost") + "b?page=2>; rel=\"next\"")));

            PagedResult failedPage = client.getAll("a?page=1", 10);
            PagedResult leaves = client.getAll("b?page=1", 10);

            assertFalse(failedPage.complete());
            assertEquals(Outcome.HTTP_ERROR, failedPage.last().outcome());
            assertFalse(leaves.complete());
            assertEquals(List.of("[1]"), leaves.pages());
        }

        @Test
        @DisplayName("parses only the next relation")
        void parsesLink() {
            assertEquals(Optional.of(URI.create("https://h/p2")),
                    CiHttpClient.nextLink("<https://h/p0>; rel=\"prev\", <https://h/p2>; rel=\"next\""));
            assertEquals(Optional.empty(), CiHttpClient.nextLink("<https://h/p0>; rel=\"prev\""));
        }
    }

    @Nested
    @DisplayName("conditional requests and rate limits")
    class ConditionalAndRateLimits {

        @Test
        @DisplayName("sends If-None-Match and reports not modified")
        void notModified() {
            server.on("GET", "/c", new Response(304, "", Map.of("ETag", "\"v1\"")));

            CiResponse response = client.get("/c", "\"v1\"");

            assertEquals(Outcome.NOT_MODIFIED, response.outcome());
            assertEquals("\"v1\"", server.requests().getFirst().header("If-None-Match"));
        }

        @Test
        @DisplayName("reports 429 with Retry-After as rate limited until the stated instant")
        void retryAfter() {
            server.on("GET", "/r1", new Response(429, "slow down", Map.of("Retry-After", "30")));

            CiResponse response = client.get("/r1");

            assertEquals(Outcome.RATE_LIMITED, response.outcome());
            assertEquals(Optional.of(NOW.plusSeconds(30)), response.resetAt());
        }

        @Test
        @DisplayName("reports an exhausted primary limit with its reset instant")
        void primaryLimit() {
            server.on("GET", "/r2", new Response(403, "{}",
                    Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1791194400")));
            server.on("GET", "/r3", new Response(429, "{}", Map.of("RateLimit-Reset", "1791194500")));
            server.on("GET", "/r4", new Response(429, "{}", Map.of("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")));

            assertEquals(Optional.of(Instant.ofEpochSecond(1791194400L)), client.get("/r2").resetAt());
            assertEquals(Optional.of(Instant.ofEpochSecond(1791194500L)), client.get("/r3").resetAt());
            assertEquals(Optional.of(NOW.plus(CiHttpClient.DEFAULT_RATE_LIMIT_WAIT)), client.get("/r4").resetAt());
        }

        @Test
        @DisplayName("keeps a plain 403 an error")
        void forbidden() {
            server.on("GET", "/f", new Response(403, "{}", Map.of("x-ratelimit-remaining", "12")));

            assertEquals(Outcome.HTTP_ERROR, client.get("/f").outcome());
        }
    }
}
