/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.credentials;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


import de.cuioss.pm.api.MachinePaths;
import lombok.experimental.UtilityClass;

/**
 * The keyring service name of an instance (doc/specification/cli-and-security/
 * 02-credentials-and-operator.adoc, service-name rule; PM-CRED-2, PM-DIST-5):
 * {@code de.cuioss.pm-mcp} for the default {@code PM_MCP_BASE}, otherwise
 * {@code de.cuioss.pm-mcp/<instance_hash>} with the first 16 hex characters of the SHA-256 of the
 * canonical absolute path of {@code <PM_MCP_BASE>}.
 */
@UtilityClass
public class ServiceName {

    /** The service name of the default instance. */
    public static final String DEFAULT = "de.cuioss.pm-mcp";

    /**
     * @param base     {@code <PM_MCP_BASE>}
     * @param userHome the user's home directory
     * @return the service name of the instance
     */
    public static String of(Path base, Path userHome) {
        var canonical = canonical(base);
        if (canonical.equals(canonical(userHome.resolve(MachinePaths.DEFAULT_BASE_DIR)))) {
            return DEFAULT;
        }
        return DEFAULT + "/" + instanceHash(canonical);
    }

    /**
     * @param canonicalBase the canonical absolute path of {@code <PM_MCP_BASE>}
     * @return the first 16 hex characters of its SHA-256
     */
    static String instanceHash(Path canonicalBase) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalBase.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    private static Path canonical(Path path) {
        var absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException _) {
            return absolute;
        }
    }
}
