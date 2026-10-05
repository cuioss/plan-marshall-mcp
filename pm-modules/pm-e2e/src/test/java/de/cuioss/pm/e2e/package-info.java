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
 * Release-shape end-to-end tests: the four binaries {@code pm-mcp}, {@code pm-operator}, {@code pm-exec} and
 * {@code pm-mcpd} staged in a temporary {@code <PM_MCP_HOME>/bin} ({@link de.cuioss.pm.e2e.ReleaseLayout}), either
 * as the native images of the sibling modules or as launcher scripts over their JARs, against a short private
 * {@code PM_MCP_BASE}. The tests run only with {@code -Pintegration-tests} and write their figures to
 * {@code target/verification-results/}.
 */
package de.cuioss.pm.e2e;
