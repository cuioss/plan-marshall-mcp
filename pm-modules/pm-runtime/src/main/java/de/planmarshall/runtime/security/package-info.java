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
 * What the authentication of the daemon resolves a presented credential through: the token kinds
 * ({@link de.planmarshall.runtime.security.CredentialKind}), the registries of job tokens and paired devices
 * ({@link de.planmarshall.runtime.security.JobTokenRegistry},
 * {@link de.planmarshall.runtime.security.DeviceRegistry}), and the constant-time comparison and digest of
 * secrets ({@link de.planmarshall.runtime.security.Secrets}).
 */
package de.planmarshall.runtime.security;
