/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.credentials;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;


import com.fasterxml.jackson.core.JsonFactory;

/**
 * Writes the figures a verification IT measured to {@code target/verification-results/<item>.json}.
 */
final class VerificationResults {

    private VerificationResults() {
    }

    /**
     * @param item   the item, for example {@code gate4-keychain-native}
     * @param values the measured values (strings, numbers, booleans)
     * @param pass   whether the pass criterion held
     * @throws IOException if the file cannot be written
     */
    static void write(String item, Map<String, Object> values, boolean pass) throws IOException {
        var json = new StringWriter();
        try (var generator = new JsonFactory().createGenerator(json)) {
            generator.writeStartObject();
            generator.writeStringField("item", item);
            generator.writeStringField("os", System.getProperty("os.name"));
            generator.writeStringField("arch", System.getProperty("os.arch"));
            generator.writeObjectFieldStart("values");
            for (var entry : values.entrySet()) {
                switch (entry.getValue()) {
                    case Boolean b -> generator.writeBooleanField(entry.getKey(), b);
                    case Long l -> generator.writeNumberField(entry.getKey(), l);
                    case Integer i -> generator.writeNumberField(entry.getKey(), i);
                    case null -> generator.writeNullField(entry.getKey());
                    default -> generator.writeStringField(entry.getKey(), entry.getValue().toString());
                }
            }
            generator.writeEndObject();
            generator.writeBooleanField("pass", pass);
            generator.writeEndObject();
        }
        var dir = Files.createDirectories(Path.of("target", "verification-results"));
        Files.writeString(dir.resolve(item + ".json"), json + "\n");
    }

    /**
     * @return {@code native} or {@code jvm}: the artifact the integration tests run against
     * @throws IOException if the Quarkus artifact descriptor cannot be read
     */
    static String packagedMode() throws IOException {
        var properties = new Properties();
        try (var in = Files.newInputStream(Path.of("target", "quarkus-artifact.properties"))) {
            properties.load(in);
        }
        return "native".equals(properties.getProperty("type")) ? "native" : "jvm";
    }
}
