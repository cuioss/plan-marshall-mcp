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
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


import com.fasterxml.jackson.databind.JsonNode;

/**
 * The staged daemon {@code pm-mcpd} running the Part A stub with a scenario: started with its own
 * {@code PM_MCP_BASE}, reached over the runtime socket with the runtime token. It mints and revokes the job token
 * of each worker generation ({@code /api/v1/spike/jobs}).
 */
final class SpikeDaemon implements AutoCloseable {

    /**
     * A minted job.
     *
     * @param jobId the job id, which the relay sends as its generation
     * @param token the job token
     */
    record Job(String jobId, String token) {
    }

    /** The MCP traffic record of the daemon ({@code pm.spike.traffic-file}) in the run directory. */
    static final String TRAFFIC_FILE = "traffic.jsonl";

    private final Process process;
    private final Path base;
    private final Path log;
    private UdsClient client;

    private SpikeDaemon(Process process, Path base, Path log) {
        this.process = process;
        this.base = base;
        this.log = log;
    }

    /**
     * Starts the daemon and waits until it serves.
     *
     * @param stage    the staged installation
     * @param base     {@code PM_MCP_BASE}, short enough for {@code sun_path}
     * @param scenario the scenario file, {@code null} for the product tool set without the stub
     * @param runDir   the directory of the stub's event log and of the MCP traffic record
     * @return the ready daemon
     * @throws IOException if it does not become ready
     */
    static SpikeDaemon start(SpikeStage stage, Path base, Path scenario, Path runDir) throws IOException {
        Files.createDirectories(base);
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
        Files.createDirectories(runDir);
        var log = runDir.resolve("daemon.log");
        var properties = new ArrayList<String>();
        if (scenario != null) {
            properties.add("-Dpm.spike.scenario=" + scenario.toAbsolutePath());
        }
        properties.add("-Dpm.spike.run-dir=" + runDir.toAbsolutePath());
        properties.add("-Dpm.spike.traffic-file=" + runDir.resolve(TRAFFIC_FILE).toAbsolutePath());
        var builder = new ProcessBuilder(stage.daemonCommand(properties)).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("PM_MCP_BASE", base.toString());
        var daemon = new SpikeDaemon(builder.start(), base, log);
        daemon.awaitReady(Duration.ofSeconds(60));
        return daemon;
    }

    private void awaitReady(Duration timeout) throws IOException {
        var deadline = System.nanoTime() + timeout.toNanos();
        var token = base.resolve("run/runtime.token");
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("daemon exited with " + process.exitValue() + ": " + output());
            }
            if (Files.exists(token) && UdsClient.accepts(socket())) {
                client = new UdsClient(socket(), Files.readString(token).strip());
                return;
            }
            sleep(50);
        }
        throw new IOException("daemon not ready within " + timeout + ": " + output());
    }

    /** @return {@code <base>/run/runtime.sock} */
    Path socket() {
        return base.resolve("run/runtime.sock");
    }

    /** @return {@code PM_MCP_BASE} */
    Path base() {
        return base;
    }

    /** @return whether the daemon process runs */
    boolean alive() {
        return process.isAlive();
    }

    /** @return the HTTP status of {@code GET /api/v1/status}, {@code -1} if the socket does not answer */
    int status() {
        try {
            return client.send("GET", "/api/v1/status", null).status();
        } catch (IOException _) {
            return -1;
        }
    }

    /** @return the daemon's pid */
    long pid() {
        return process.pid();
    }

    /**
     * @param worker     the worker
     * @param generation the generation
     * @param role       the role
     * @return the job and its token
     * @throws IOException if the daemon refuses
     */
    Job mint(String worker, int generation, String role) throws IOException {
        var body = StubEvent.JSON.createObjectNode().put("worker", worker).put("generation", generation)
                .put("role", role).toString();
        var response = client.send("POST", "/api/v1/spike/jobs", body);
        if (response.status() != 201) {
            throw new IOException("mint refused: " + response.status() + " " + response.body());
        }
        var json = StubEvent.JSON.readTree(response.body());
        return new Job(json.get("job_id").asText(), json.get("token").asText());
    }

    /**
     * Revokes a job: its generation is fenced in the stub.
     *
     * @param jobId the job
     * @return the answer, with the tasks released at the fence
     * @throws IOException if the daemon refuses
     */
    JsonNode revoke(String jobId) throws IOException {
        var response = client.send("DELETE", "/api/v1/spike/jobs/" + jobId, null);
        if (response.status() != 200) {
            throw new IOException("revoke refused: " + response.status() + " " + response.body());
        }
        return StubEvent.JSON.readTree(response.body());
    }

    /** @return the daemon's output so far */
    String output() {
        try {
            return Files.exists(log) ? Files.readString(log) : "";
        } catch (IOException e) {
            return e.toString();
        }
    }

    /** @return the last lines of the daemon's output */
    List<String> tail() {
        var lines = output().lines().toList();
        return lines.subList(Math.max(0, lines.size() - 20), lines.size());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (client != null && process.isAlive()) {
            try {
                client.send("POST", "/api/v1/runtime/stop", null);
            } catch (IOException _) {
                // fall through to the signal
            }
        }
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
