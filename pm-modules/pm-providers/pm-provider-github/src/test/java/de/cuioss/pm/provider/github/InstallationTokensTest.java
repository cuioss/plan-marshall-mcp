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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.cuioss.pm.provider.ci.CiEndpoint;
import de.cuioss.pm.provider.ci.CiResult;
import de.cuioss.pm.provider.ci.Json;
import de.cuioss.pm.provider.github.FakeServer.Response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InstallationTokens")
class InstallationTokensTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final String PATH = "/app/installations/42/access_tokens";
    private static final Map<String, String> PERMISSIONS = Map.of("pull_requests", "write", "contents", "read");

    /** A clock the test moves. */
    static final class MovableClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private FakeServer server;
    private MovableClock clock;
    private List<String> redacted;
    private InstallationTokens tokens;

    @BeforeEach
    void start() {
        server = new FakeServer();
        clock = new MovableClock();
        redacted = new ArrayList<>();
        tokens = new InstallationTokens(CiEndpoint.of(server.base()), "Iv23liClient", () -> TestKeys.PKCS8_PEM,
                redacted::add, clock);
    }

    @AfterEach
    void stop() {
        tokens.close();
        server.close();
    }

    private static Response minted(String token, Instant expiresAt) {
        return Response.json(201, "{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt + "\"}");
    }

    @Test
    @DisplayName("mints with a verifiable JWT, narrowed to the repository and permissions")
    void mints() throws Exception {
        server.on("POST", PATH, minted("ghs_one", NOW.plusSeconds(3600)));

        CiResult<InstallationTokens.InstallationToken> result = tokens.token(42, "plan-marshall-mcp", PERMISSIONS);

        assertTrue(result.isOk());
        assertEquals("ghs_one", result.value().orElseThrow().token());
        assertFalse(result.value().orElseThrow().toString().contains("ghs_one"));
        var request = server.requests().getFirst();
        String authorization = request.header("Authorization");
        assertTrue(authorization.startsWith("Bearer "));
        Object claims = Json.parse(TestKeys.verifiedClaims(authorization.substring(7), TestKeys.PAIR.getPublic()));
        assertEquals(Optional.of("Iv23liClient"), Json.string(claims, "iss"));
        assertEquals("{\"repositories\":[\"plan-marshall-mcp\"],\"permissions\":{\"contents\":\"read\","
                + "\"pull_requests\":\"write\"}}", request.body());
        assertEquals("2022-11-28", request.header("X-GitHub-Api-Version"));
        assertEquals(List.of("ghs_one"), redacted);
    }

    @Test
    @DisplayName("mints the token of a pull-request comment with pull_requests:write, signed with a PKCS#1 key")
    void mintsForOperationClass() throws Exception {
        server.on("POST", PATH, minted("ghs_comment", NOW.plusSeconds(3600)));
        try (var pkcs1 = new InstallationTokens(CiEndpoint.of(server.base()), "Iv23liClient", () -> TestKeys.PKCS1_PEM,
                     redacted::add, Clock.fixed(NOW, ZoneOffset.UTC))) {

            var result = pkcs1.token(42, "plan-marshall-mcp", GitHubOperation.PULL_REQUEST_COMMENT);

            assertTrue(result.isOk());
        }
        var request = server.requests().getFirst();
        TestKeys.verifiedClaims(request.header("Authorization").substring(7), TestKeys.PAIR.getPublic());
        assertEquals("{\"repositories\":[\"plan-marshall-mcp\"],\"permissions\":{\"pull_requests\":\"write\"}}",
                request.body());
    }

    @Test
    @DisplayName("reuses a token until the reuse margin, then mints with a fresh JWT")
    void reusesUntilMargin() {
        server.on("POST", PATH, minted("ghs_one", NOW.plusSeconds(3600)));
        server.on("POST", PATH, minted("ghs_two", NOW.plusSeconds(7200)));

        String first = tokens.token(42, "repo", PERMISSIONS).value().orElseThrow().token();
        clock.now = NOW.plusSeconds(3600 - 301);
        String reused = tokens.tokenSource(42, "repo", PERMISSIONS).token().orElseThrow();
        clock.now = NOW.plusSeconds(3600 - 300);
        String renewed = tokens.token(42, "repo", PERMISSIONS).value().orElseThrow().token();

        assertEquals("ghs_one", first);
        assertEquals("ghs_one", reused);
        assertEquals("ghs_two", renewed);
        assertEquals(2, server.requests().size());
        assertNotEquals(server.requests().get(0).header("Authorization"), server.requests().get(1).header("Authorization"), "a JWT is never reused");
    }

    @Test
    @DisplayName("keys the cache by permission set")
    void keyedByPermissions() {
        server.on("POST", PATH, minted("ghs_read", NOW.plusSeconds(3600)));
        server.on("POST", PATH, minted("ghs_write", NOW.plusSeconds(3600)));

        tokens.token(42, "repo", Map.of("contents", "read"));
        String write = tokens.token(42, "repo", Map.of("contents", "write")).value().orElseThrow().token();

        assertEquals("ghs_write", write);
    }

    @Test
    @DisplayName("reports refused mints, unreadable responses and unusable keys")
    void failures() {
        server.on("POST", PATH, Response.json(401, "{\"message\":\"A JSON web token could not be decoded\"}"));
        server.on("POST", "/app/installations/7/access_tokens", Response.json(201, "{\"token\":\"t\"}"));
        server.on("POST", "/app/installations/8/access_tokens",
                Response.json(201, "{\"token\":\"t\",\"expires_at\":\"tomorrow\"}"));

        assertEquals(CiResult.Outcome.AUTH_FAILED, tokens.token(42, "repo", PERMISSIONS).outcome());
        assertEquals(CiResult.Outcome.FAILED, tokens.token(7, "repo", PERMISSIONS).outcome());
        assertEquals(CiResult.Outcome.FAILED, tokens.token(8, "repo", PERMISSIONS).outcome());
        assertTrue(tokens.tokenSource(42, "repo", PERMISSIONS).token().isEmpty());
        try (var broken = new InstallationTokens(CiEndpoint.of(server.base()), "id", () -> "garbage", redacted::add,
                     clock)) {
            assertEquals(CiResult.Outcome.AUTH_FAILED, broken.token(42, "repo", PERMISSIONS).outcome());
        }
        assertTrue(redacted.isEmpty());
    }
}
