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

import java.security.Permission;
import java.security.Principal;
import java.util.Map;
import java.util.Set;


import io.quarkus.security.credential.Credential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;

/**
 * The security identity of an authenticated request: the credential kind, the principal it resolved to, and
 * the attributes of the request's connection metadata (doc/specification/runtime-model/03-relay.adoc,
 * Connection Metadata). Handlers read these attributes, never an HTTP header.
 * <p>
 * The identity has no roles and no permissions; resources are only authenticated.
 *
 * @param kind       the credential kind
 * @param name       the principal name: {@code runtime}, the job id, or the device id
 * @param attributes the identity attributes, see {@link IdentityAttributes}
 * @since 0.1
 */
public record PmIdentity(CredentialKind kind, String name, Map<String, Object> attributes)
        implements SecurityIdentity {

    /**
     * @param kind       the credential kind
     * @param name       the principal name
     * @param attributes the identity attributes
     */
    public PmIdentity {
        attributes = Map.copyOf(attributes);
    }

    @Override
    public Principal getPrincipal() {
        return () -> name;
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }

    @Override
    public Set<String> getRoles() {
        return Set.of();
    }

    @Override
    public boolean hasRole(String role) {
        return false;
    }

    @Override
    public Set<Permission> getPermissions() {
        return Set.of();
    }

    @Override
    public <T extends Credential> T getCredential(Class<T> type) {
        return null;
    }

    @Override
    public Set<Credential> getCredentials() {
        return Set.of();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getAttribute(String name) {
        return (T) attributes.get(name);
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Uni<Boolean> checkPermission(Permission permission) {
        return Uni.createFrom().item(Boolean.FALSE);
    }
}
