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
 * Harness drivers of roadmap Milestone 0, Part B: gate 13 (headless capabilities under target conditions),
 * gate 9 (MCP call idle timeout and progress reset) and gate 16 (the E2 fault matrix against the real worker
 * relay {@code pm-mcp serve --job} and the real daemon). They run real harness CLIs and cost model tokens, so
 * every IT is opt-in: {@code -Dspike.harness=claude|opencode|codex} and {@code -Dspike.item=gate13|gate9|gate16}.
 * <p>
 * The daemon runs the Part A stub ({@code pm.spike.scenario}); the supervisor mints one job token per worker
 * generation through {@code /api/v1/spike/jobs}, starts each worker through {@code pm-exec} (confined by
 * Landlock on Linux), detects lost workers from process exits and the stub's event log, and revokes the token
 * of a replaced generation.
 * <p>
 * The host-session drivers run a harness as a host, not a worker, with the session relay
 * {@code pm-mcp serve --client <id>} in front of the daemon's product tool set: gate 11 (the ten core tools listed
 * and called) and gate 9 (relay start and end with the host session), opt-in with
 * {@code -Dspike.harness=claude|opencode|antigravity} and {@code -Dspike.item=gate11|gate9-relay}. Both read the
 * daemon's MCP traffic record ({@code pm.spike.traffic-file}). Deleted at the Part B exit.
 */
package de.cuioss.pm.e2e.spike;
