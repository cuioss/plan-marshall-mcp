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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


import de.cuioss.pm.mcp.server.security.Secrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Job tokens of the harness drivers")
class SpikeJobsTest {

    private final SpikeJobs jobs = new SpikeJobs();

    @Nested
    @DisplayName("mint")
    class Mint {

        @Test
        @DisplayName("binds a fresh token to the worker generation")
        void shouldBindToken() {
            var minted = jobs.mint("w1", 2, "worker");

            var resolved = jobs.resolve(Secrets.sha256(minted.token())).orElseThrow();
            assertEquals("j-w1-g2", resolved.jobId());
            assertEquals("w1", resolved.worker());
            assertEquals(2, resolved.generation());
            assertEquals("worker", resolved.role());
            assertFalse(resolved.revoked());
            assertTrue(minted.token().startsWith("pmj_"));
        }

        @Test
        @DisplayName("replaces the token when the same generation is minted again")
        void shouldReplaceToken() {
            var first = jobs.mint("w1", 1, null);
            var second = jobs.mint("w1", 1, null);

            assertNotEquals(first.token(), second.token());
            assertTrue(jobs.resolve(Secrets.sha256(first.token())).isEmpty());
            assertTrue(jobs.resolve(Secrets.sha256(second.token())).isPresent());
        }
    }

    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("marks the job revoked and keeps its token resolving")
        void shouldKeepResolving() {
            var minted = jobs.mint("w2", 1, "worker");

            var revoked = jobs.revoke("j-w2-g1").orElseThrow();

            assertTrue(revoked.revoked());
            assertTrue(jobs.resolve(Secrets.sha256(minted.token())).orElseThrow().revoked());
            assertTrue(jobs.job("j-w2-g1").orElseThrow().revoked());
        }

        @Test
        @DisplayName("answers empty for an unknown job")
        void shouldIgnoreUnknown() {
            assertTrue(jobs.revoke("j-none-g1").isEmpty());
            assertTrue(jobs.job(null).isEmpty());
            assertTrue(jobs.resolve(Secrets.sha256("unknown")).isEmpty());
        }
    }
}
