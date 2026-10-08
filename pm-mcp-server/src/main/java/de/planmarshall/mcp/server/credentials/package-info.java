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
 * The credential resource of the local API and the producer of the active store: the local API writes,
 * deletes and reports the status of an entry at {@code /api/v1/credentials/{key}}
 * ({@link de.planmarshall.mcp.server.credentials.CredentialsResource}) and never returns a secret; the stores
 * themselves are {@link de.planmarshall.runtime.credentials.SecretStore} of {@code pm-runtime}.
 *
 * @since 0.1
 */
package de.planmarshall.mcp.server.credentials;
