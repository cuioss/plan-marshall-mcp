/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
/**
 * Throwaway stub for the harness-as-worker measurements V1 to V10 and E1 to E13 of roadmap Milestone 0, Part A
 * ({@code doc/specification/evaluation.adoc}): a blocking wait tool, scripted tasks and an event log.
 * <p>
 * The tools are registered only when {@code pm.spike.scenario} is configured. In Part B the stub runs behind the
 * real worker relay {@code pm-mcp serve --job}: the harness drivers of {@code pm-e2e} mint and revoke one job token
 * per worker generation ({@link de.cuioss.pm.mcp.spike.SpikeJobResource}), and the stub takes the worker identity
 * from the request's security identity. Independent of the stub, {@code pm.spike.traffic-file} records every MCP
 * message ({@link de.cuioss.pm.mcp.spike.SpikeTrafficRecord}). The package is removed when
 * Milestone 1 creates the target module structure.
 */
package de.cuioss.pm.mcp.spike;
