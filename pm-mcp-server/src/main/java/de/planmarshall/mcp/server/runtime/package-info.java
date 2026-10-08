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
 * Runtime lifecycle of {@code pm-mcpd}: the entry point, the startup sequence (singleton lock, stale-file
 * cleanup, directory and permission verification, runtime token, socket bind and runtime record) and the
 * identity of the running instance.
 */
package de.planmarshall.mcp.server.runtime;
