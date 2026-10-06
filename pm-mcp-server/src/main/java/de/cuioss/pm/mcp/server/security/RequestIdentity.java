/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.security;

import java.util.Optional;


import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The security identity of the request a tool or resource handler serves.
 * <p>
 * Without the {@code quarkus-security} extension there is no {@code CurrentIdentityAssociation} bean, so
 * neither {@code @Inject SecurityIdentity} works nor does the MCP server propagate the identity into CDI. The
 * MCP server does activate the request context with the request's routing context
 * ({@link CurrentVertxRequest}); the identity the mechanism built is the routing context's user.
 *
 * @since 0.1
 */
@ApplicationScoped
public class RequestIdentity {

    private final CurrentVertxRequest currentRequest;

    /**
     * @param currentRequest the request-scoped holder of the current routing context
     */
    public RequestIdentity(CurrentVertxRequest currentRequest) {
        this.currentRequest = currentRequest;
    }

    /**
     * @return the identity of the current request, or empty outside a request or for an anonymous one
     */
    public Optional<PmIdentity> current() {
        var context = currentRequest.getCurrent();
        if (context != null && context.user() instanceof QuarkusHttpUser user
                && user.getSecurityIdentity() instanceof PmIdentity identity) {
            return Optional.of(identity);
        }
        return Optional.empty();
    }
}
