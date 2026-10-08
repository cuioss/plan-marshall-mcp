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

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The API endpoint of a provider: its base URI and the origin the token is pinned to.
 *
 * @param baseUri        the API base, e.g. {@code https://api.github.com/} or
 *                       {@code https://gitlab.example.com/api/v4/}; always ends with {@code /}
 * @param redirectHosts  hosts a redirect may lead to (e.g. a log storage host); the token is never
 *                       sent there
 * @since 0.1
 */
public record CiEndpoint(URI baseUri, Set<String> redirectHosts) {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    /**
     * @param baseUri       the base URI
     * @param redirectHosts the redirect hosts
     */
    public CiEndpoint {
        Objects.requireNonNull(baseUri, "baseUri");
        redirectHosts = Set.copyOf(redirectHosts);
        if (!baseUri.getPath().endsWith("/")) {
            baseUri = URI.create(baseUri + "/");
        }
        String scheme = String.valueOf(baseUri.getScheme()).toLowerCase(Locale.ROOT);
        boolean loopback = baseUri.getHost() != null && LOOPBACK.contains(baseUri.getHost().toLowerCase(Locale.ROOT));
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            throw new IllegalArgumentException("API endpoints are https only (http on loopback): " + baseUri);
        }
    }

    /**
     * @param baseUri the https API base
     * @return an endpoint without redirect hosts
     */
    public static CiEndpoint of(URI baseUri) {
        return new CiEndpoint(baseUri, Set.of());
    }

    /**
     * @return whether the endpoint is a cleartext loopback endpoint (local fakes)
     */
    public boolean cleartextLoopback() {
        return "http".equalsIgnoreCase(baseUri.getScheme());
    }

    /**
     * @param uri an absolute URI
     * @return whether {@code uri} has the endpoint's origin (scheme, host and effective port)
     */
    public boolean sameOrigin(URI uri) {
        return uri.getScheme() != null && uri.getHost() != null
                && baseUri.getScheme().equalsIgnoreCase(uri.getScheme())
                && baseUri.getHost().equalsIgnoreCase(uri.getHost())
                && port(baseUri) == port(uri);
    }

    /**
     * @param pathOrUri a path relative to the base, or an absolute URI
     * @return the absolute URI
     */
    public URI resolve(String pathOrUri) {
        String relative = pathOrUri.startsWith("/") ? pathOrUri.substring(1) : pathOrUri;
        return baseUri.resolve(relative);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
