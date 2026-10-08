/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import de.planmarshall.api.json.JsonTree;

/**
 * Writes the figures a verification IT measured to {@code target/verification-results/<item>.json}.
 */
public final class VerificationResults {

    private VerificationResults() {
    }

    /**
     * @param item   the verification item, the file name
     * @param values the measured values (strings, numbers as {@code Long}, booleans)
     * @param pass   whether the item passed
     * @return the written file
     * @throws IOException on a write failure
     */
    public static Path write(String item, Map<String, Object> values, boolean pass) throws IOException {
        var root = new LinkedHashMap<String, Object>();
        root.put("item", item);
        root.put("os", System.getProperty("os.name"));
        root.put("arch", System.getProperty("os.arch"));
        root.put("values", values);
        root.put("pass", pass);
        var directory = Path.of("target", "verification-results");
        Files.createDirectories(directory);
        var file = directory.resolve(item + ".json");
        Files.writeString(file, JsonTree.write(root) + "\n");
        return file;
    }
}
