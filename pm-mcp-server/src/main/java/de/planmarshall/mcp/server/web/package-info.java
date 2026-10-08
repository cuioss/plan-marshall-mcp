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
 * The web adapter: the optional second HTTP server on TCP (loopback, or LAN with a self-signed TLS
 * certificate), {@code Host} validation, the Origin check, the security headers, and the route restriction to
 * the static web app and {@code /api/v1/}.
 */
package de.planmarshall.mcp.server.web;
