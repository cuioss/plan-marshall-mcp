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
 * The CI contract's shared HTTP base over {@code cui-http}.
 * <p>
 * {@link de.planmarshall.provider.ci.CiHttpClient} is the one HTTP client of the native provider
 * clients (GitHub, GitLab, Sonar): it pins the bearer token to the configured origin (the token is
 * sent only there, and {@code cui-http} strips it on a redirect to another origin), follows
 * {@code Link} pagination with a completeness flag, sends conditional requests with an
 * {@code ETag}, and reports a provider rate limit as an outcome with its reset instant, never as a
 * failure.
 */
package de.planmarshall.provider.ci;
