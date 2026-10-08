/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.relay;

/**
 * The identity a relay carries: a host session (declared client and workspace) or a worker (its
 * generation).
 *
 * @param job        {@code true} for a worker relay
 * @param client     the declared client of a session relay, {@code null} for a worker
 * @param workspace  the workspace of a session relay, {@code null} for a worker
 * @param generation the generation, {@code null} while unknown
 */
record RelayMode(boolean job, String client, String workspace, String generation) {

    /**
     * @param client    the declared client
     * @param workspace the workspace
     * @return a session relay
     */
    static RelayMode session(String client, String workspace) {
        return new RelayMode(false, client, workspace, null);
    }

    /**
     * @param generation the worker's generation ({@code PM_MCP_JOB_ID})
     * @return a worker relay
     */
    static RelayMode worker(String generation) {
        return new RelayMode(true, null, null, generation);
    }
}
