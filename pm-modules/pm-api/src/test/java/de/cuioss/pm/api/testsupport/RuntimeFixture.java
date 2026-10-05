/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import de.cuioss.pm.api.MachinePaths;

/**
 * A machine root {@code <PM_MCP_BASE>} below {@code /tmp} (short enough for {@code sun_path}) with a
 * runtime token and, on request, a {@link FakeRuntimeServer} on its socket that checks the runtime
 * token or a job token like the runtime does.
 */
public final class RuntimeFixture implements AutoCloseable {

    private final Path base;
    private final MachinePaths paths;
    private volatile String token;
    private volatile String jobToken;
    private FakeRuntimeServer server;

    private RuntimeFixture(Path base) {
        this.base = base;
        this.paths = new MachinePaths(base, MachinePaths.Os.current());
    }

    /**
     * @return a fixture with an empty machine root that does not exist yet
     * @throws IOException if the temporary directory cannot be created
     */
    public static RuntimeFixture create() throws IOException {
        var parent = Files.createTempDirectory(Path.of("/tmp"), "pm");
        return new RuntimeFixture(parent.resolve("b"));
    }

    /** @return the machine root (absent until a token is written) */
    public Path base() {
        return base;
    }

    /** @return the machine paths */
    public MachinePaths paths() {
        return paths;
    }

    /** @return an environment naming the machine root */
    public Map<String, String> environment() {
        var environment = new HashMap<String, String>();
        environment.put(MachinePaths.ENV_BASE, base.toString());
        return environment;
    }

    /** @return the current runtime token */
    public String token() {
        return token;
    }

    /**
     * Creates {@code <base>} and {@code run/} ({@code 0700}) and writes the token ({@code 0600}).
     *
     * @param value the token
     * @throws IOException on a write failure
     */
    public void writeToken(String value) throws IOException {
        Files.createDirectories(paths.runDir());
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(paths.runDir(), PosixFilePermissions.fromString("rwx------"));
        var temp = paths.runDir().resolve("runtime.token.tmp");
        Files.writeString(temp, value);
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        Files.move(temp, paths.runtimeToken(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        token = value;
    }

    /**
     * Changes the token the server accepts without rewriting the file (a stale client token).
     *
     * @param value the accepted token
     */
    public void acceptToken(String value) {
        token = value;
    }

    /**
     * @param value the job token the server accepts on {@code /mcp}
     */
    public void acceptJobToken(String value) {
        jobToken = value;
    }

    /**
     * Writes a token (if none) and starts the fake runtime on the socket.
     *
     * @param handler the handler of authenticated requests
     * @return the server
     * @throws IOException if it cannot be started
     */
    public FakeRuntimeServer start(FakeRuntimeServer.Handler handler) throws IOException {
        if (token == null) {
            writeToken("token-1");
        }
        server = FakeRuntimeServer.start(paths.socket(), (request, response) -> {
            if (authorized(request)) {
                handler.handle(request, response);
            } else {
                response.send(401, null, "");
            }
        });
        return server;
    }

    private boolean authorized(FakeRuntimeServer.Request request) {
        var bearer = request.header("Authorization");
        if (bearer != null) {
            return bearer.equals("Bearer " + token);
        }
        var job = request.header("PM-MCP-Job-Token");
        return job != null && job.equals(jobToken) && request.target().startsWith("/mcp");
    }

    /**
     * Stops the server, keeping the files.
     *
     * @throws IOException on a close failure
     */
    public void stop() throws IOException {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Override
    public void close() throws IOException {
        stop();
        var root = base.getParent();
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
