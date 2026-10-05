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
 * Runtime access of the client binaries: the runtime-token client check, the authenticated client
 * with its single {@code 401} retry, the on-demand start procedure under
 * {@code locks/runtime-start.lock}, the detached spawn of {@code pm-mcpd}, the resolution of
 * {@code PM_MCP_HOME}, and the read-only codec of the runtime record.
 */
package de.cuioss.pm.api.runtime;
