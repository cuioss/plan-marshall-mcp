/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.security;

import java.util.Map;
import java.util.Optional;
import java.util.Set;


import de.planmarshall.mcp.server.web.WebListener;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The one HTTP authentication mechanism of the runtime (Bearer, plus {@code PM-MCP-Job-Token} on {@code /mcp}).
 * <p>
 * The listener a request arrived on decides which token kind it may present; no request header changes that:
 * <ul>
 * <li>Unix socket: {@code PM-MCP-Job-Token} on {@code /mcp} is a job token; otherwise the Bearer value is
 * checked as the runtime token (a device secret therefore fails there).</li>
 * <li>Web listener: the Bearer value is checked as a device secret only (the runtime token and a job token
 * therefore fail there).</li>
 * </ul>
 * A request without a credential stays anonymous and is refused {@code 401} by the path policy. The mechanism
 * turns the relay's {@code PM-MCP-*} headers into attributes of the security identity.
 *
 * @since 0.1
 */
@ApplicationScoped
public class TokenAuthenticationMechanism implements HttpAuthenticationMechanism {

    /** Path of the MCP endpoint, the only path that accepts a job token. */
    static final String MCP_PATH = "/mcp";

    private static final String BEARER = "Bearer ";

    private final WebListener webListener;

    /**
     * @param webListener the web listener, which knows its connections
     */
    public TokenAuthenticationMechanism(WebListener webListener) {
        this.webListener = webListener;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        var request = context.request();
        var web = webListener.serves(request.connection());
        var listener = web ? IdentityAttributes.LISTENER_WEB : IdentityAttributes.LISTENER_UNIX;
        var bearer = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        var jobToken = request.getHeader(IdentityAttributes.JOB_TOKEN_HEADER);
        Optional<TokenRequest> authRequest;
        if (web) {
            authRequest = bearer.map(secret -> new TokenRequest.DeviceSecret(secret,
                    Map.of(IdentityAttributes.LISTENER, listener,
                            IdentityAttributes.CREDENTIAL, "device")));
        } else if (jobToken != null && isMcp(context.normalizedPath())) {
            var metadata = IdentityAttributes.connectionMetadata(request.headers(), CredentialKind.JOB);
            metadata.put(IdentityAttributes.LISTENER, listener);
            authRequest = Optional.of(new TokenRequest.JobToken(jobToken, metadata));
        } else {
            var metadata = IdentityAttributes.connectionMetadata(request.headers(), CredentialKind.RUNTIME);
            metadata.put(IdentityAttributes.LISTENER, listener);
            authRequest = bearer.map(token -> new TokenRequest.RuntimeToken(token, metadata));
        }
        if (authRequest.isEmpty()) {
            return Uni.createFrom().optional(Optional.empty());
        }
        HttpSecurityUtils.setRoutingContextAttribute(authRequest.get(), context);
        return identityProviderManager.authenticate(authRequest.get());
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Bearer"));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(TokenRequest.RuntimeToken.class, TokenRequest.JobToken.class, TokenRequest.DeviceSecret.class);
    }

    static boolean isMcp(String path) {
        return path != null && (MCP_PATH.equals(path) || path.startsWith(MCP_PATH + "/"));
    }

    static Optional<String> bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        var value = authorization.substring(BEARER.length()).strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
