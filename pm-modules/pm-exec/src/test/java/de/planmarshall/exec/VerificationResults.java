/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes the figures a verification IT measured to {@code target/verification-results/<item>.json}.
 */
final class VerificationResults {

    private VerificationResults() {
    }

    /**
     * @param item   the item name, for example {@code gate3-pm-exec-macos}
     * @param values the measured values; strings are quoted, other values written as they are
     * @param pass   whether the pass criterion held
     * @throws IOException if the file cannot be written
     */
    static void write(String item, Map<String, Object> values, boolean pass) throws IOException {
        var body = values.entrySet().stream()
                .map(e -> Json.quote(e.getKey()) + ":" + value(e.getValue()))
                .collect(Collectors.joining(","));
        var json = "{\"item\":" + Json.quote(item) + ",\"os\":" + Json.quote(System.getProperty("os.name"))
                + ",\"arch\":" + Json.quote(System.getProperty("os.arch")) + ",\"values\":{" + body + "},\"pass\":"
                + pass + "}\n";
        var dir = Files.createDirectories(Path.of("target", "verification-results"));
        Files.writeString(dir.resolve(item + ".json"), json);
    }

    private static String value(Object value) {
        if (value instanceof String s) {
            return s.startsWith("{") ? s : Json.quote(s);
        }
        return String.valueOf(value);
    }
}
