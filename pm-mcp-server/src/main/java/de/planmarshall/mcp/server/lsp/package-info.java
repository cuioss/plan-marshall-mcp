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
 * The product LSP client of the warm language-server pool: a language server started as a child
 * process, spoken to with Eclipse LSP4J (JSON-RPC 2.0 over {@code stdio}), with every request bounded
 * by a timeout and a clean {@code shutdown} / {@code exit} / termination sequence.
 */
package de.planmarshall.mcp.server.lsp;
