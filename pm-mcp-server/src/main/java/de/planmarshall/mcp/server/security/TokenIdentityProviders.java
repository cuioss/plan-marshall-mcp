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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;


import de.planmarshall.mcp.server.runtime.RuntimeContext;
import de.planmarshall.runtime.security.DeviceRegistry;
import de.planmarshall.runtime.security.JobTokenRegistry;
import de.planmarshall.runtime.security.Secrets;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.experimental.UtilityClass;

/**
 * The identity providers, one per token kind (doc/specification/cli-and-security/03-web-server.adoc,
 * Authentication Seam). Each compares in constant time and builds a {@link PmIdentity} from the request's
 * connection metadata.
 *
 * @since 0.1
 */
@UtilityClass
public final class TokenIdentityProviders {

    /** Attribute name prefix of the identity attributes taken over from the request. */
    static final String ATTRIBUTE_PREFIX = "pm.";

    static Uni<SecurityIdentity> identity(TokenRequest request, String name, Map<String, Object> extra) {
        var attributes = new HashMap<String, Object>();
        request.getAttributes().forEach((key, value) -> {
            if (key.startsWith(ATTRIBUTE_PREFIX)) {
                attributes.put(key, value);
            }
        });
        attributes.putAll(extra);
        return Uni.createFrom().item(new PmIdentity(request.kind(), name, attributes));
    }

    static Uni<SecurityIdentity> refused() {
        return Uni.createFrom().failure(new AuthenticationFailedException());
    }

    /** Accepts the runtime token of this runtime instance. */
    @ApplicationScoped
    public static class RuntimeTokenProvider implements IdentityProvider<TokenRequest.RuntimeToken> {

        private final RuntimeContext context;

        /**
         * @param context the runtime identity holding the token
         */
        public RuntimeTokenProvider(RuntimeContext context) {
            this.context = context;
        }

        @Override
        public Class<TokenRequest.RuntimeToken> getRequestType() {
            return TokenRequest.RuntimeToken.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(TokenRequest.RuntimeToken request,
                AuthenticationRequestContext requestContext) {
            if (Secrets.constantTimeEquals(context.tokenBytes(), request.token().getBytes(StandardCharsets.UTF_8))) {
                return identity(request, "runtime", Map.of());
            }
            return refused();
        }
    }

    /** Accepts a job token that resolves to a job; the mechanism offers it on {@code /mcp} only. */
    @ApplicationScoped
    public static class JobTokenProvider implements IdentityProvider<TokenRequest.JobToken> {

        private final JobTokenRegistry registry;

        /**
         * @param registry the job token registry
         */
        public JobTokenProvider(JobTokenRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Class<TokenRequest.JobToken> getRequestType() {
            return TokenRequest.JobToken.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(TokenRequest.JobToken request,
                AuthenticationRequestContext requestContext) {
            return registry.resolve(Secrets.sha256(request.token()))
                    .map(job -> identity(request, job.jobId(), Map.of(IdentityAttributes.JOB_ID, job.jobId())))
                    .orElseGet(TokenIdentityProviders::refused);
        }
    }

    /** Accepts a paired device's secret; the mechanism offers it on the web listener only. */
    @ApplicationScoped
    public static class DeviceSecretProvider implements IdentityProvider<TokenRequest.DeviceSecret> {

        private final DeviceRegistry registry;

        /**
         * @param registry the web device store
         */
        public DeviceSecretProvider(DeviceRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Class<TokenRequest.DeviceSecret> getRequestType() {
            return TokenRequest.DeviceSecret.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(TokenRequest.DeviceSecret request,
                AuthenticationRequestContext requestContext) {
            if (!request.token().startsWith(DeviceRegistry.SECRET_PREFIX)) {
                return refused();
            }
            return registry.resolve(Secrets.sha256(request.token()))
                    .map(device -> identity(request, device, Map.of(IdentityAttributes.DEVICE_ID, device)))
                    .orElseGet(TokenIdentityProviders::refused);
        }
    }
}
