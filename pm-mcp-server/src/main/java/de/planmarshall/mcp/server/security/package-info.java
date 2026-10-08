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
 * Authentication of every request on both listeners: one HTTP authentication mechanism, one identity provider
 * per token kind (runtime token, job token, device secret), and the security identity that carries the relay's
 * connection metadata to the handlers.
 */
package de.planmarshall.mcp.server.security;
