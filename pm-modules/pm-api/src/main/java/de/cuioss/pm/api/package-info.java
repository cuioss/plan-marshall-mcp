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
 * Client contract of the PM-MCP daemon: the machine paths, the socket client, the on-demand start
 * and the runtime-token checks shared by {@code pm-mcp} and {@code pm-operator}.
 * <p>
 * Depends only on the JDK and the streaming API of {@code jackson-core}.
 */
package de.cuioss.pm.api;
