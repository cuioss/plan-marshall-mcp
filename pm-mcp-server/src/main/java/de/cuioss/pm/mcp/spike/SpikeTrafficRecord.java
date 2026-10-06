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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;


import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.McpTrafficListener;
import io.quarkiverse.mcp.server.RawMessage;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Records every MCP message the daemon receives and sends as one JSONL line
 * ({@code {"t_ms":…,"dir":"rx|tx","connection":…,"message":{…}}}), so the harness drivers of {@code pm-e2e} verify
 * from the daemon side which tools a host listed and called, and how the daemon answered each method (gates 9, 10
 * and 11 of Milestone 0, Part B).
 * <p>
 * Active only when {@code pm.spike.traffic-file} names the file; independent of the pull-mechanism stub, so it
 * runs in front of the product tool set.
 */
@ApplicationScoped
public class SpikeTrafficRecord implements McpTrafficListener {

    private final Path file;

    /**
     * @param file the JSONL file; the recorder stays inactive without it
     */
    SpikeTrafficRecord(@ConfigProperty(name = "pm.spike.traffic-file") Optional<String> file) {
        this.file = file.map(Path::of).orElse(null);
    }

    @Override
    public boolean isEnabled() {
        return file != null;
    }

    @Override
    public void onMessageReceived(RawMessage message, McpConnection connection) {
        record("rx", message, connection);
    }

    @Override
    public void onMessageSent(RawMessage message, McpConnection connection) {
        record("tx", message, connection);
    }

    private void record(String direction, RawMessage message, McpConnection connection) {
        Object body;
        try {
            body = message.asJsonObject();
        } catch (DecodeException _) {
            body = message.asString();
        }
        record(direction, body, connection == null ? null : connection.id());
    }

    /**
     * Records a message that bypassed the MCP server (an answer of {@link SpikeListenFilter}).
     *
     * @param direction  {@code rx} or {@code tx}
     * @param body       the message
     * @param connection a connection label, may be {@code null}
     */
    synchronized void record(String direction, Object body, String connection) {
        if (file == null) {
            return;
        }
        var line = new JsonObject().put("t_ms", System.currentTimeMillis()).put("dir", direction)
                .put("connection", connection).put("message", body);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, line.encode() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
