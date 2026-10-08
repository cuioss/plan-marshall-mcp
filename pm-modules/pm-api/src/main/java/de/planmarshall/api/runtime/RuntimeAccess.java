/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.api.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import lombok.experimental.UtilityClass;

import de.planmarshall.api.MachinePaths;
import de.planmarshall.api.http.UnixSocketHttpClient;
import de.planmarshall.api.posix.PosixPlatform;
import de.planmarshall.api.posix.PosixSpawn;

/**
 * Wires the runtime clients of the two binaries from the process environment.
 */
@UtilityClass
public class RuntimeAccess {

    /**
     * The client of a local session or operator command, authenticated with the runtime token.
     *
     * @param environment   the process environment
     * @param userHome      the user's home directory
     * @param headers       headers sent on every request (for example the client version)
     * @param startOnDemand {@code true} to start the runtime when none answers
     * @return the client
     * @throws IOException if the effective user id cannot be read
     */
    public static RuntimeClient local(Map<String, String> environment, Path userHome, Map<String, String> headers,
            boolean startOnDemand) throws IOException {
        var os = MachinePaths.Os.current();
        var paths = MachinePaths.resolve(environment, userHome, os);
        var platform = PosixPlatform.of(os);
        var tokenFile = new RuntimeTokenFile(paths, new PosixSpawn(platform).effectiveUserId());
        if (!startOnDemand) {
            return RuntimeClient.local(paths, tokenFile, null, headers);
        }
        var probe = RuntimeClient.local(paths, tokenFile, null, headers);
        var starter = OnDemandStart.of(paths, () -> PmMcpHome.current(environment).daemon(), probe::isLive,
                new PosixDaemonLauncher(platform, environment));
        return RuntimeClient.local(paths, tokenFile, starter, headers);
    }

    /**
     * The client of a worker relay: the job token from the environment, the socket path from the
     * relay's arguments, no file of {@code <PM_MCP_BASE>} read, and never a runtime start.
     *
     * @param socket   the socket path
     * @param jobToken the job token
     * @param headers  headers sent on every request
     * @return the client
     */
    public static RuntimeClient job(Path socket, String jobToken, Map<String, String> headers) {
        return new RuntimeClient(new UnixSocketHttpClient(socket), Authenticator.jobToken(jobToken), null, headers);
    }
}
