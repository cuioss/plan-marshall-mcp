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
 * Runtime lifecycle of {@code pm-mcpd}: the entry point, the socket bind with the runtime record after the
 * start sequence of {@code pm-runtime} ({@link de.planmarshall.runtime.start.StartupSequence}), the Netty
 * transport, and the identity of the running instance.
 */
package de.planmarshall.mcp.server.runtime;
