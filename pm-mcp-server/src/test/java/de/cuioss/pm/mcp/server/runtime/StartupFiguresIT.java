/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.pm.mcp.server.test.DaemonProcess;
import de.cuioss.pm.mcp.server.test.TestBases;
import de.cuioss.pm.mcp.server.test.VerificationResults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The figures of PM-TECH-3 for the packaged daemon: cold start to ready (socket accepting and token present,
 * median of 20 starts on a clean state) and the idle resident set size 10 s after ready. The budgets (50 ms,
 * 64 MB) apply to the native binary; for the JVM runner the figures are recorded only.
 */
@DisplayName("Startup and memory figures of the packaged daemon (PM-TECH-3)")
class StartupFiguresIT {

    private static final int STARTS = 20;
    private static final Duration READY = Duration.ofSeconds(30);

    @Test
    @DisplayName("measures cold start to ready and idle RSS")
    void shouldMeasure() throws Exception {
        var millis = new ArrayList<Double>();
        for (int i = 0; i < STARTS; i++) {
            var base = TestBases.create("pmf");
            try {
                var started = System.nanoTime();
                try (var daemon = DaemonProcess.start(base, List.of())) {
                    daemon.awaitReady(READY);
                    millis.add((System.nanoTime() - started) / 1_000_000.0);
                }
            } finally {
                TestBases.delete(base);
            }
        }
        var sorted = millis.stream().sorted().toList();
        var median = (sorted.get(STARTS / 2 - 1) + sorted.get(STARTS / 2)) / 2;

        long rssKb;
        var base = TestBases.create("pmr");
        try (var daemon = DaemonProcess.startReady(base)) {
            Thread.sleep(10_000);
            rssKb = rss(daemon.process().pid());
        } finally {
            TestBases.delete(base);
        }

        var nativeRunner = DaemonProcess.isNative();
        var values = new LinkedHashMap<String, Object>();
        values.put("cold_start_median_ms", Math.round(median * 10) / 10.0);
        values.put("cold_start_min_ms", Math.round(sorted.getFirst() * 10) / 10.0);
        values.put("cold_start_max_ms", Math.round(sorted.getLast() * 10) / 10.0);
        values.put("starts", STARTS);
        values.put("idle_rss_mb", Math.round(rssKb / 102.4) / 10.0);
        values.put("budget_cold_start_ms", 50);
        values.put("budget_idle_rss_mb", 64);
        var pass = nativeRunner && median <= 50 && rssKb <= 64 * 1024;
        VerificationResults.write("gate1-pm-tech-3-figures", values, pass);
        if (nativeRunner) {
            Path runner;
            try (var files = Files.list(Path.of("target"))) {
                runner = files.filter(file -> file.toString().endsWith("-runner")).findFirst().orElseThrow();
            }
            VerificationResults.write("gate1-native-binary", Map.of("size_mb",
                    Math.round(Files.size(runner) / 104857.6) / 10.0), true);
        }

        assertEquals(STARTS, millis.size());
        assertTrue(rssKb > 0);
    }

    private static long rss(long pid) throws IOException, InterruptedException {
        var process = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid)).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip();
        process.waitFor();
        return Long.parseLong(output);
    }
}
