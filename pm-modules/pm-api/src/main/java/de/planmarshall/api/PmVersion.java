/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import lombok.experimental.UtilityClass;

/**
 * The release version of the client binaries, filtered into the resource
 * {@code de/planmarshall/api/version.properties} at build time (registered as a native-image resource).
 */
@UtilityClass
public class PmVersion {

    /** Returned when the resource is missing. */
    public static final String UNKNOWN = "unknown";

    private static final String RESOURCE = "/de/planmarshall/api/version.properties";
    private static final String VERSION = load();

    /** @return the release version */
    public static String current() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = PmVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return UNKNOWN;
            }
            var properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", UNKNOWN);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }
}
