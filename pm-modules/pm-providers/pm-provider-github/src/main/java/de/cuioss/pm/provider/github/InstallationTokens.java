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

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.cuioss.pm.provider.ci.CiEndpoint;
import de.cuioss.pm.provider.ci.CiHttpClient;
import de.cuioss.pm.provider.ci.CiResponse;
import de.cuioss.pm.provider.ci.CiResult;
import de.cuioss.pm.provider.ci.Json;
import de.cuioss.pm.provider.ci.TokenSource;

/**
 * Mints GitHub App installation tokens through {@code POST /app/installations/{id}/access_tokens},
 * narrowed to one repository and a permission set, and holds them in memory only.
 * <p>
 * Each mint request is authenticated with a freshly signed {@link GitHubAppJwt}; the private key is
 * read per mint and never cached. A minted token is registered with the redaction registry and
 * reused, keyed by installation, repository and permission set, until the derived token reuse
 * margin ({@value #REUSE_MARGIN_SECONDS} s) before its {@code expires_at}.
 *
 * @since 0.1
 */
public final class InstallationTokens implements AutoCloseable {

    /** Seconds before {@code expires_at} from which a token is no longer used. */
    public static final long REUSE_MARGIN_SECONDS = 300;

    /** GitHub's REST headers. */
    static final Map<String, String> HEADERS = Map.of("Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", "2022-11-28");

    private final CiHttpClient http;
    private final Clock clock;
    private final Consumer<String> redaction;
    private final Map<Key, InstallationToken> cache = new ConcurrentHashMap<>();

    private record Key(long installationId, String repository, SortedMap<String, String> permissions) {
    }

    /**
     * A minted installation token.
     *
     * @param token     the token
     * @param expiresAt its expiry
     */
    public record InstallationToken(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "InstallationToken[***, expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * @param apiEndpoint   the API endpoint ({@code https://api.github.com} or the Enterprise API)
     * @param clientId      the App's client id
     * @param privateKeyPem reads the PKCS#8 private key per mint
     * @param redaction     the redaction registry receiving every minted token
     * @param clock         the clock
     */
    public InstallationTokens(CiEndpoint apiEndpoint, String clientId, Supplier<String> privateKeyPem,
            Consumer<String> redaction, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.redaction = Objects.requireNonNull(redaction, "redaction");
        TokenSource jwt = () -> Optional.of(GitHubAppJwt.sign(clientId, privateKeyPem.get(), clock.instant()));
        this.http = new CiHttpClient(apiEndpoint, jwt, HEADERS);
    }

    /**
     * Returns a reusable token or mints a new one.
     *
     * @param installationId the installation
     * @param repository     the repository name the token is narrowed to
     * @param permissions    the permission set, e.g. {@code pull_requests -> write}
     * @return the token, or the outcome of the failed mint
     */
    public CiResult<InstallationToken> token(long installationId, String repository, Map<String, String> permissions) {
        var key = new Key(installationId, repository, new TreeMap<>(permissions));
        InstallationToken cached = cache.get(key);
        if (cached != null && clock.instant().isBefore(cached.expiresAt().minusSeconds(REUSE_MARGIN_SECONDS))) {
            return CiResult.ok(cached);
        }
        CiResult<InstallationToken> minted = mint(key);
        minted.value().ifPresent(t -> cache.put(key, t));
        return minted;
    }

    /**
     * @param installationId the installation
     * @param repository     the repository
     * @param permissions    the permission set
     * @return a token source for {@link CiHttpClient} that mints or reuses per request
     */
    public TokenSource tokenSource(long installationId, String repository, Map<String, String> permissions) {
        return () -> token(installationId, repository, permissions).value().map(InstallationToken::token);
    }

    private CiResult<InstallationToken> mint(Key key) {
        var body = new LinkedHashMap<String, Object>();
        body.put("repositories", List.of(key.repository()));
        body.put("permissions", new LinkedHashMap<>(key.permissions()));
        CiResponse response;
        try {
            response = http.send("POST", "app/installations/" + key.installationId() + "/access_tokens",
                    Optional.of(Json.write(body)), Optional.empty());
        } catch (GitHubAppJwt.JwtSigningException e) {
            return CiResult.of(CiResult.Outcome.UNAUTHORIZED, e.getMessage());
        }
        if (!response.isOk()) {
            return CiResult.failed(response);
        }
        Object json = Json.parseOrNull(response.body());
        Optional<String> token = Json.string(json, "token");
        Optional<String> expiresAt = Json.string(json, "expires_at");
        if (token.isEmpty() || expiresAt.isEmpty()) {
            return CiResult.of(CiResult.Outcome.FAILED, "mint response without token or expires_at");
        }
        try {
            var minted = new InstallationToken(token.get(), Instant.parse(expiresAt.get()));
            redaction.accept(minted.token());
            return CiResult.ok(minted);
        } catch (DateTimeParseException e) {
            return CiResult.of(CiResult.Outcome.FAILED, "unreadable expires_at");
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
