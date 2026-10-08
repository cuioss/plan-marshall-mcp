/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.http;

/**
 * One dispatched Server-Sent Event.
 *
 * @param event the event type, {@code message} when the stream named none
 * @param data  the data lines joined by LF
 * @param id    the last event id seen on the stream, {@code null} when none
 */
public record SseEvent(String event, String data, String id) {
}
