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
 * The deterministic, reflection-free TOON encoder of PM-MCP.
 * <p>
 * {@link de.planmarshall.core.toon.ToonEncoder} encodes a small immutable value tree
 * ({@link de.planmarshall.core.toon.ToonValue}) to TOON text conforming to the official TOON
 * specification version {@value de.planmarshall.core.toon.ToonEncoder#SPEC_VERSION}: every form of the
 * specification (inline, tabular with nested field groups, list and keyed tabular form) with the
 * canonical options only, the comma delimiter and an indentation of two spaces. A value TOON cannot
 * represent (an unpaired surrogate, a duplicate key) is refused with a
 * {@link de.planmarshall.core.toon.ToonEncodingException}.
 */
package de.planmarshall.core.toon;
