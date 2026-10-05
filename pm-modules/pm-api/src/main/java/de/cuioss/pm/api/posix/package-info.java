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
 * The detached spawn of the runtime through FFM {@code posix_spawn(3)} with
 * {@code POSIX_SPAWN_SETSID}: the platform constants ({@link de.cuioss.pm.api.posix.PosixPlatform}),
 * the plan of one spawn in plain Java ({@link de.cuioss.pm.api.posix.SpawnPlan}), and the downcalls
 * ({@link de.cuioss.pm.api.posix.PosixSpawn}).
 */
package de.cuioss.pm.api.posix;
