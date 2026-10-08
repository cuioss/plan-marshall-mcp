/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.credentials.dbus;

/**
 * A D-Bus variant ({@code v}): a value with its own single complete type.
 *
 * @param signature the type of the value
 * @param value     the value, mapped as described in the package documentation
 */
public record Variant(String signature, Object value) {
}
