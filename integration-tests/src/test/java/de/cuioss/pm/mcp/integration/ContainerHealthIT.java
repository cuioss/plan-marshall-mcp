/*
 * Copyright © 2026-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.pm.mcp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verifies the image's own {@code HEALTHCHECK} via the Docker CLI.
 */
@DisplayName("Container health check")
class ContainerHealthIT {

    private static final String IMAGE = "plan-marshall-mcp:jvm";
    private static final Duration HEALTHY_TIMEOUT = Duration.ofSeconds(90);

    @Test
    @DisplayName("image declares an exec-form health probe")
    void imageShouldDeclareExecFormProbe() throws Exception {
        var test = docker("image", "inspect", "--format", "{{json .Config.Healthcheck.Test}}", IMAGE);

        assertTrue(test.startsWith("[\"CMD\","), "HEALTHCHECK must be exec-form, was: " + test);
        assertTrue(test.contains("--health-probe"), "HEALTHCHECK must invoke the health probe, was: " + test);
    }

    @Test
    @DisplayName("running container becomes healthy")
    void containerShouldBecomeHealthy() throws Exception {
        var containerId = docker("ps", "-q", "--filter", "ancestor=" + IMAGE);
        assertTrue(!containerId.isBlank() && containerId.lines().count() == 1,
                "Exactly one running container expected for " + IMAGE + ", got: " + containerId);

        var deadline = Instant.now().plus(HEALTHY_TIMEOUT);
        var status = "";
        while (Instant.now().isBefore(deadline)) {
            status = docker("inspect", "--format", "{{.State.Health.Status}}", containerId);
            if ("healthy".equals(status) || "unhealthy".equals(status)) {
                break;
            }
            TimeUnit.SECONDS.sleep(2);
        }
        assertEquals("healthy", status);
    }

    private static String docker(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        // Read concurrently: readAllBytes() blocks until the process exits, which would bypass the timeout
        var outputFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("docker command timed out: " + command);
        }
        var output = outputFuture.join();
        assertEquals(0, process.exitValue(), "docker command failed: " + command + "\n" + output);
        return output;
    }
}
