/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;


import io.quarkiverse.mcp.server.RawMessage;
import io.quarkiverse.mcp.server.RequestId;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Spike MCP traffic record and listen experiment")
class SpikeTrafficRecordTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("stays inactive without pm.spike.traffic-file")
    void shouldStayInactive() {
        var record = new SpikeTrafficRecord(Optional.empty());

        record.record("rx", new JsonObject(), null);

        assertFalse(record.isEnabled());
    }

    @Test
    @DisplayName("writes one JSONL line per received and sent message")
    void shouldRecordBothDirections() throws Exception {
        var file = dir.resolve("sub/traffic.jsonl");
        var record = new SpikeTrafficRecord(Optional.of(file.toString()));

        record.onMessageReceived(message("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"), null);
        record.onMessageSent(message("not json"), null);

        assertTrue(record.isEnabled());
        var lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        var first = new JsonObject(lines.getFirst());
        assertEquals("rx", first.getString("dir"));
        assertEquals("tools/list", first.getJsonObject("message").getString("method"));
        assertEquals("not json", new JsonObject(lines.get(1)).getString("message"));
    }

    private static RawMessage message(String text) {
        return new RawMessage() {
            @Override
            public JsonObject asJsonObject() {
                return new JsonObject(text);
            }

            @Override
            public String asString() {
                return text;
            }

            @Override
            public String asPrettyString() {
                return text;
            }

            @Override
            public RequestId id() {
                return null;
            }

            @Override
            public String method() {
                return null;
            }
        };
    }
}
