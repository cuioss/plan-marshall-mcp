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
 * The native GitHub client of PM-MCP: GitHub App authentication (an RS256 JWT signed with the JDK,
 * installation-token minting with an in-memory reuse margin), fixed GraphQL documents for review
 * threads and the merge queue, and REST calls, all over the shared HTTP base of
 * {@code pm-provider-ci}.
 */
package de.planmarshall.provider.github;
