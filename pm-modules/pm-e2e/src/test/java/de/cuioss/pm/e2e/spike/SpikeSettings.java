/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The opt-in flags of the harness drivers, read from system properties ({@code -D…} on the Maven command line).
 *
 * @param harness          {@code spike.harness}: the harness under test
 * @param model            {@code spike.model}: the model, default per harness
 * @param layout           {@code spike.layout}: {@code native}, {@code jvm} or {@code auto} (native when built)
 * @param trials           {@code spike.trials}: trials per fault (gate 16), default 10
 * @param faults           {@code spike.faults}: {@code all} or a comma-separated list (gate 16)
 * @param workers          {@code spike.workers}: concurrent workers (gate 16), default 2
 * @param waitSeconds      {@code spike.wait-s}: bounded wait of the stub, default 20 (Part A)
 * @param graceSeconds     {@code spike.grace-s}: silence grace, default 30
 * @param stopShortSeconds {@code spike.stop-short-s}: stall of stop-short, default 16
 * @param stopLongSeconds  {@code spike.stop-long-s}: stall of stop-long, default 70
 * @param exitDetectMaxMs  {@code spike.exit-detect-max-ms}: accepted kill-to-detection time, default 1500
 * @param cellDeadlineSeconds {@code spike.deadline-s}: bound of one fault cell, default 3600
 * @param trialTimeoutSeconds {@code spike.trial-timeout-s}: bound of one trial, default 300
 * @param sizes            {@code spike.sizes}: task input sizes in characters (gate 13), default 8000,32000,128000
 * @param idleMaxSeconds   {@code spike.idle-max-s}: upper bound of the idle-timeout measurement (gate 9)
 * @param progressSeconds  {@code spike.progress-s}: progress interval of the progress variant (gate 9), default 5
 * @param extraWrites      {@code spike.write-extra}: comma-separated paths added to the Landlock write set
 */
record SpikeSettings(Harness harness, String model, String layout, int trials, List<Fault> faults, int workers,
        int waitSeconds, int graceSeconds, int stopShortSeconds, int stopLongSeconds, long exitDetectMaxMs,
        int cellDeadlineSeconds, int trialTimeoutSeconds, List<Integer> sizes, int idleMaxSeconds, int progressSeconds,
        List<Path> extraWrites) {

    /** System property naming the harness; every driver IT requires it. */
    static final String HARNESS = "spike.harness";
    /** System property naming the item; every driver IT requires its own. */
    static final String ITEM = "spike.item";
    /** The pattern of {@link #HARNESS} that enables the ITs. */
    static final String HARNESSES = "claude|opencode|codex";

    /** @return the settings of this JVM's system properties */
    static SpikeSettings fromSystemProperties() {
        var harness = Harness.of(System.getProperty(HARNESS, "claude"));
        return new SpikeSettings(harness, System.getProperty("spike.model", harness.defaultModel()),
                System.getProperty("spike.layout", "auto").toLowerCase(Locale.ROOT), integer("spike.trials", 10),
                Fault.parse(System.getProperty("spike.faults", "all")), integer("spike.workers", 2),
                integer("spike.wait-s", 20), integer("spike.grace-s", 30), integer("spike.stop-short-s", 16),
                integer("spike.stop-long-s", 70), integer("spike.exit-detect-max-ms", 1500),
                integer("spike.deadline-s", 3600), integer("spike.trial-timeout-s", 300),
                integers(System.getProperty("spike.sizes", "8000,32000,128000")),
                integer("spike.idle-max-s", harness.idleMaxSeconds()), integer("spike.progress-s", 5),
                paths(System.getProperty("spike.write-extra", "")));
    }

    /** @return bounded wait plus grace in milliseconds */
    long silenceLimitMs() {
        return (waitSeconds + graceSeconds) * 1000L;
    }

    private static int integer(String name, int fallback) {
        var value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
    }

    static List<Integer> integers(String list) {
        var values = new ArrayList<Integer>();
        for (var part : list.split(",")) {
            if (!part.isBlank()) {
                values.add(Integer.valueOf(part.strip()));
            }
        }
        return List.copyOf(values);
    }

    private static List<Path> paths(String list) {
        var values = new ArrayList<Path>();
        for (var part : list.split(",")) {
            if (!part.isBlank()) {
                values.add(Path.of(part.strip()).toAbsolutePath());
            }
        }
        return List.copyOf(values);
    }
}
