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

/**
 * Resolves a job token by its SHA-256 to the job it was minted for. Only the hash is stored (job record);
 * a revoked token still resolves, so its calls are answered by the tool handler, never {@code 401}.
 *
 * @since 0.1
 */
public interface JobTokenRegistry {

    /**
     * A job the token is bound to.
     *
     * @param jobId the job id, which is also the worker's generation
     */
    record JobBinding(String jobId) {
    }

    /**
     * @param tokenSha256 the SHA-256 of the presented token
     * @return the job, or empty for an unknown token
     */
    Optional<JobBinding> resolve(byte[] tokenSha256);
}
