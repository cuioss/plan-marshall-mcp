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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the figures of a driver IT to {@code target/verification-results/<item>.json}
 * ({@code {"item":…,"os":…,"arch":…,"values":{…},"pass":…}}) and names the run directories.
 */
final class SpikeResults {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMdd-HHmmss", Locale.ROOT);

    private SpikeResults() {
    }

    /**
     * @param item   the item, e.g. {@code gate16-claude}
     * @param values the figures
     * @param pass   whether the pass criterion held
     * @return the written file
     * @throws IOException if it cannot be written
     */
    static Path write(String item, Map<String, Object> values, boolean pass) throws IOException {
        var result = new LinkedHashMap<String, Object>();
        result.put("item", item);
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("values", values);
        result.put("pass", pass);
        var file = Path.of("target", "verification-results", item + ".json").toAbsolutePath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, StubEvent.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        return file;
    }

    /**
     * @param path  a file
     * @param value a value Jackson can write
     * @throws IOException if it cannot be written
     */
    static void json(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, StubEvent.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    /**
     * @param name the run name, e.g. {@code gate16-claude-kill-offer}
     * @return a fresh directory {@code target/spike-runs/<name>-<stamp>}
     * @throws IOException if it cannot be created
     */
    static Path runDirectory(String name) throws IOException {
        return Files.createDirectories(Path.of("target", "spike-runs", name + "-" + LocalDateTime.now().format(STAMP))
                .toAbsolutePath());
    }

    /**
     * @param name the run name
     * @return a fresh worker workspace outside every repository ({@code <tmp>/pm-spike-ws/<name>…}), marked with
     *         this process as its owner; the login copies that killed drivers left in other workspaces are deleted
     * @throws IOException if it cannot be created
     */
    static Path workspace(String name) throws IOException {
        var root = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "pm-spike-ws"));
        OpencodeData.removeStaleLogins(root, pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        var workspace = Files.createTempDirectory(root, name + "-").toRealPath();
        OpencodeData.markOwner(workspace, ProcessHandle.current().pid());
        return workspace;
    }

    /**
     * @return a fresh {@code PM_MCP_BASE} whose socket path fits {@code sun_path}
     * @throws IOException if it cannot be created
     */
    static Path base() throws IOException {
        var tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        var candidate = Files.createTempDirectory(tmp, "pmv");
        if (candidate.resolve("run/runtime.sock").toString().length() < 100) {
            return candidate;
        }
        Files.delete(candidate);
        return Files.createTempDirectory(Path.of("/tmp").toRealPath(), "pmv");
    }

    /** @return the common figures of the environment */
    static Map<String, Object> environment(SpikeSettings settings, SpikeStage stage) {
        var values = new LinkedHashMap<String, Object>();
        values.put("harness", settings.harness().id());
        values.put("harness_version", settings.harness().version());
        values.put("model", settings.model());
        values.put("layout", stage.mode());
        values.put("confined", WorkerLauncher.confined());
        values.put("java", System.getProperty("java.version"));
        return values;
    }
}
