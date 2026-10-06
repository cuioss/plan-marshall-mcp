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
 * {@link de.cuioss.pm.core.toon.ToonEncoder} encodes a small immutable value tree
 * ({@link de.cuioss.pm.core.toon.ToonValue}) to TOON text conforming to the official TOON
 * specification version {@value de.cuioss.pm.core.toon.ToonEncoder#SPEC_VERSION} for the subset
 * PM-MCP emits: objects, tabular object arrays with primitive columns, length-prefixed inline
 * primitive arrays and minimal string quoting with the official escapes. A value whose shape the
 * official specification would render in a form outside that subset (list form, keyed tabular form,
 * nested field groups) is refused with a {@link de.cuioss.pm.core.toon.ToonEncodingException}
 * instead of being rendered non-conformantly.
 */
package de.cuioss.pm.core.toon;
