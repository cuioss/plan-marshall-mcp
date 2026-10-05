/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;


import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/** Writes a verification figure set to {@code target/verification-results/<item>.json} (test helper). */
public final class VerificationResult {

    private VerificationResult() {
    }

    /**
     * @param item   the item, e.g. {@code gate2-lsp4j-native}
     * @param values the measured figures (strings, numbers, booleans)
     * @param pass   whether the item passed
     */
    public static void write(String item, Map<String, Object> values, boolean pass) {
        Path file = Path.of("target", "verification-results", item + ".json");
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                 JsonGenerator json = new JsonFactory().createGenerator(out)) {
                json.useDefaultPrettyPrinter();
                json.writeStartObject();
                json.writeStringField("item", item);
                json.writeStringField("os", System.getProperty("os.name"));
                json.writeStringField("arch", System.getProperty("os.arch"));
                json.writeObjectFieldStart("values");
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    json.writeFieldName(entry.getKey());
                    switch (entry.getValue()) {
                        case Number number -> json.writeNumber(number.toString());
                        case Boolean bool -> json.writeBoolean(bool);
                        default -> json.writeString(String.valueOf(entry.getValue()));
                    }
                }
                json.writeEndObject();
                json.writeBooleanField("pass", pass);
                json.writeEndObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
