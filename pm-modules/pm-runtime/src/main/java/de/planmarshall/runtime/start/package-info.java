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
 * The start sequence of the runtime up to the socket bind: the singleton lock
 * ({@link de.planmarshall.runtime.start.RuntimeLock}), the removal of a stale socket and runtime record under
 * it, the verification of the base directory and its permissions
 * ({@link de.planmarshall.runtime.start.BaseDirectoryCheck}), and a fresh runtime token
 * ({@link de.planmarshall.runtime.start.RuntimeTokenFile}), in the order of
 * {@link de.planmarshall.runtime.start.StartupSequence}.
 */
package de.planmarshall.runtime.start;
