/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.security;

/**
 * The token kinds the runtime accepts, each with its own identity provider. The listener a request arrived on
 * fixes which kinds count: the runtime token everywhere on the Unix socket, the job token on {@code /mcp} of
 * the Unix socket only, the device secret on the web listener only.
 *
 * @since 0.1
 */
public enum CredentialKind {

    /** The runtime token of {@code run/runtime.token}, presented by every local client. */
    RUNTIME,

    /** A worker's job token, header {@code PM-MCP-Job-Token}. */
    JOB,

    /** A paired browser's device secret ({@code pmw_…}). */
    DEVICE
}
