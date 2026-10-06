/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;


import io.vertx.core.json.JsonObject;

/**
 * Writes the figures a verification IT measured to {@code target/verification-results/<item>.json}.
 */
public final class VerificationResults {

    private VerificationResults() {
    }

    /**
     * @param item   the verification item, for example {@code gate1-cold-start}
     * @param values the measured values
     * @param pass   whether the item passed
     */
    public static void write(String item, Map<String, Object> values, boolean pass) {
        var json = new JsonObject()
                .put("item", item)
                .put("os", System.getProperty("os.name"))
                .put("arch", System.getProperty("os.arch"))
                .put("runner", DaemonProcess.isNative() ? "native" : "jvm")
                .put("values", new JsonObject(values))
                .put("pass", pass);
        try {
            var dir = Files.createDirectories(Path.of("target", "verification-results"));
            Files.writeString(dir.resolve(item + ".json"), json.encodePrettily());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
