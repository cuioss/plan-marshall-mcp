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
 * A minimal D-Bus client for the Linux Secret Service backend (PM-CRED-2): the wire format
 * ({@link de.planmarshall.runtime.credentials.dbus.DbusWriter}, {@link de.planmarshall.runtime.credentials.dbus.DbusReader},
 * {@link de.planmarshall.runtime.credentials.dbus.DbusMessage}) and a connection over the JDK's
 * {@code UnixDomainSocketAddress} channel with SASL {@code EXTERNAL} authentication
 * ({@link de.planmarshall.runtime.credentials.dbus.DbusConnection}).
 * <p>
 * The type system covers what the Secret Service needs: {@code y b n q i u x t s o g v}, arrays
 * (with {@code byte[]} for {@code ay}, {@link java.util.Map} for dictionaries, {@link java.util.List}
 * otherwise) and structs ({@link java.util.List}). Values are aligned to their natural boundary
 * relative to the message start, as the specification requires.
 *
 * @since 0.1
 */
package de.planmarshall.runtime.credentials.dbus;
