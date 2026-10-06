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
 * The credential store of the runtime (PM-CRED-2, PM-CRED-3; doc/specification/cli-and-security/
 * 02-credentials-and-operator.adoc, OS Keyring Backends &amp; Fallback File Store): exactly one active
 * {@link de.cuioss.pm.mcp.server.credentials.SecretStore} per machine, chosen at runtime start by
 * {@link de.cuioss.pm.mcp.server.credentials.SecretStoreSelector}: the macOS login Keychain
 * (Security.framework through FFM), the Linux Secret Service (D-Bus over the JDK Unix domain socket
 * channel), otherwise the permission-guarded file store below {@code <PM_MCP_BASE>/credentials/}.
 * Every keyring entry lives under one service name ({@link de.cuioss.pm.mcp.server.credentials.ServiceName}).
 * The local API exposes the store at {@code /api/v1/credentials/{key}}
 * ({@link de.cuioss.pm.mcp.server.credentials.CredentialsResource}).
 *
 * @since 0.1
 */
package de.cuioss.pm.mcp.server.credentials;
