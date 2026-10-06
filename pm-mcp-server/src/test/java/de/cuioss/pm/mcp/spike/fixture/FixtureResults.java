/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


import de.cuioss.pm.provider.ci.Json;

/**
 * Writes nested figures to {@code target/verification-results/<item>.json}, replacing every registered secret
 * (each minted token) with {@code ***} before anything reaches the disk.
 */
final class FixtureResults {

    private final Set<String> secrets = ConcurrentHashMap.newKeySet();

    void secret(String secret) {
        if (secret != null && !secret.isEmpty()) {
            secrets.add(secret);
        }
    }

    String redact(String text) {
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, "***");
        }
        return result;
    }

    void write(String item, Map<String, Object> values, boolean pass) throws IOException {
        var root = new LinkedHashMap<String, Object>();
        root.put("item", item);
        root.put("os", System.getProperty("os.name"));
        root.put("arch", System.getProperty("os.arch"));
        root.put("values", values);
        root.put("pass", pass);
        Path dir = Files.createDirectories(Path.of("target", "verification-results"));
        Files.writeString(dir.resolve(item + ".json"), redact(Json.write(root)) + "\n");
    }
}
