/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;


import com.fasterxml.jackson.core.JsonFactory;

/**
 * Writes the figures an IT measured to {@code target/verification-results/<item>.json}.
 */
final class VerificationResults {

    private VerificationResults() {
    }

    /**
     * @param item   the verification item, the file name
     * @param values the measured values (strings, numbers, booleans, {@code null})
     * @param pass   whether the item passed
     * @throws IOException on a write failure
     */
    static void write(String item, Map<String, Object> values, boolean pass) throws IOException {
        var json = new StringWriter();
        try (var generator = new JsonFactory().createGenerator(json)) {
            generator.useDefaultPrettyPrinter();
            generator.writeStartObject();
            generator.writeStringField("item", item);
            generator.writeStringField("os", System.getProperty("os.name"));
            generator.writeStringField("arch", System.getProperty("os.arch"));
            generator.writeObjectFieldStart("values");
            for (var entry : values.entrySet()) {
                switch (entry.getValue()) {
                    case null -> generator.writeNullField(entry.getKey());
                    case Boolean b -> generator.writeBooleanField(entry.getKey(), b);
                    case Long l -> generator.writeNumberField(entry.getKey(), l);
                    case Integer i -> generator.writeNumberField(entry.getKey(), i);
                    default -> generator.writeStringField(entry.getKey(), entry.getValue().toString());
                }
            }
            generator.writeEndObject();
            generator.writeBooleanField("pass", pass);
            generator.writeEndObject();
        }
        var directory = Files.createDirectories(Path.of("target", "verification-results"));
        Files.writeString(directory.resolve(item + ".json"), json + "\n");
    }
}
