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
 * The daemon's logic that needs no framework (PM-IMPL-1): the runtime start sequence, the credential stores,
 * the certificate of the web listener, the language server client, the ingestion validator, and the registries
 * the authentication of {@code pm-mcpd} resolves tokens through. No class of this module references a Quarkus,
 * CDI, Vert.x or MCP type; the assembly {@code pm-mcp-server} produces its services through CDI.
 * {@link de.planmarshall.runtime.PmMcpLogMessages} holds the log messages of the daemon.
 */
package de.planmarshall.runtime;
