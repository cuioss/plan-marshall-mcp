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
 * The minimal HTTP/1.1 and Server-Sent Events client over the runtime's Unix domain socket, shared
 * by {@code pm-mcp} and {@code pm-operator}: JDK {@code SocketChannel} on a
 * {@code UnixDomainSocketAddress}, one connection per exchange, unbuffered event delivery.
 */
package de.planmarshall.api.http;
