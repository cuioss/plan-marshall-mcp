/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


import de.cuioss.pm.mcp.server.security.Secrets;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The job tokens of the harness drivers of Milestone 0, Part B: one token per worker generation, minted and
 * revoked at runtime through {@link SpikeJobResource}.
 * <p>
 * Only the SHA-256 of a token is kept. A revoked token still resolves (as a job token of the product does), so
 * the calls of a replaced generation reach the stub and are refused there as {@code stale_generation}, never
 * with {@code 401}.
 */
@ApplicationScoped
public class SpikeJobs {

    private static final int TOKEN_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Job> byHash = new ConcurrentHashMap<>();
    private final Map<String, Job> byId = new ConcurrentHashMap<>();

    /**
     * One worker generation.
     *
     * @param jobId      the job id, {@code j-<worker>-g<generation>}
     * @param worker     the worker
     * @param generation the generation of the worker
     * @param role       the role whose tasks the worker pulls, {@code null} for any
     * @param revoked    whether the job was revoked
     */
    public record Job(String jobId, String worker, int generation, String role, boolean revoked) {
    }

    /**
     * A freshly minted job and its token, which is never stored.
     *
     * @param job   the job
     * @param token the job token
     */
    public record Minted(Job job, String token) {
    }

    /**
     * Mints a job token for one worker generation; minting the same generation again replaces its token.
     *
     * @param worker     the worker
     * @param generation the generation
     * @param role       the role, may be {@code null}
     * @return the job and its token
     */
    public Minted mint(String worker, int generation, String role) {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        var token = "pmj_" + HEX.formatHex(bytes);
        var job = new Job("j-" + worker + "-g" + generation, worker, generation, role, false);
        var previous = byId.put(job.jobId(), job);
        if (previous != null) {
            byHash.values().removeIf(known -> known.jobId().equals(job.jobId()));
        }
        byHash.put(HEX.formatHex(Secrets.sha256(token)), job);
        return new Minted(job, token);
    }

    /**
     * Marks a job as revoked; its token keeps resolving.
     *
     * @param jobId the job id
     * @return the revoked job, or empty for an unknown id
     */
    public Optional<Job> revoke(String jobId) {
        var known = byId.get(jobId);
        if (known == null) {
            return Optional.empty();
        }
        var revoked = new Job(known.jobId(), known.worker(), known.generation(), known.role(), true);
        byId.put(jobId, revoked);
        byHash.replaceAll((hash, job) -> job.jobId().equals(jobId) ? revoked : job);
        return Optional.of(revoked);
    }

    /**
     * @param tokenSha256 the SHA-256 of a presented token
     * @return the job, or empty for an unknown token
     */
    public Optional<Job> resolve(byte[] tokenSha256) {
        return Optional.ofNullable(byHash.get(HEX.formatHex(tokenSha256)));
    }

    /**
     * @param jobId a job id
     * @return the job, or empty for an unknown id
     */
    public Optional<Job> job(String jobId) {
        return Optional.ofNullable(jobId).map(byId::get);
    }
}
