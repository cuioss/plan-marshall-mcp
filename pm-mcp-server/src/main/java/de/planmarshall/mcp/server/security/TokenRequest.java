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


import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/**
 * An authentication request carrying one presented token; one subclass per {@link CredentialKind}, so that
 * Quarkus dispatches each to its own identity provider. The connection metadata travels as request attributes.
 *
 * @since 0.1
 */
public abstract sealed class TokenRequest extends BaseAuthenticationRequest
        permits TokenRequest.RuntimeToken, TokenRequest.JobToken, TokenRequest.DeviceSecret {

    private final String token;

    TokenRequest(String token, Map<String, Object> metadata) {
        this.token = token;
        metadata.forEach(this::setAttribute);
    }

    /** @return the presented token */
    public String token() {
        return token;
    }

    /** @return the credential kind of this request */
    public abstract CredentialKind kind();

    /** A runtime token presented as {@code Authorization: Bearer} on the Unix socket. */
    public static final class RuntimeToken extends TokenRequest {

        /**
         * @param token    the presented token
         * @param metadata the connection metadata
         */
        public RuntimeToken(String token, Map<String, Object> metadata) {
            super(token, metadata);
        }

        @Override
        public CredentialKind kind() {
            return CredentialKind.RUNTIME;
        }
    }

    /** A job token presented as {@code PM-MCP-Job-Token} on {@code /mcp} of the Unix socket. */
    public static final class JobToken extends TokenRequest {

        /**
         * @param token    the presented token
         * @param metadata the connection metadata
         */
        public JobToken(String token, Map<String, Object> metadata) {
            super(token, metadata);
        }

        @Override
        public CredentialKind kind() {
            return CredentialKind.JOB;
        }
    }

    /** A device secret presented as {@code Authorization: Bearer pmw_…} on the web listener. */
    public static final class DeviceSecret extends TokenRequest {

        /**
         * @param token    the presented secret
         * @param metadata the request attributes
         */
        public DeviceSecret(String token, Map<String, Object> metadata) {
            super(token, metadata);
        }

        @Override
        public CredentialKind kind() {
            return CredentialKind.DEVICE;
        }
    }
}
