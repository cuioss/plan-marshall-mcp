/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.verify.lsp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.pm.mcp.server.lsp.LspClient;
import de.cuioss.pm.mcp.server.lsp.LspException;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code POST /api/v1/spike/lsp}: starts the language server named by {@code command}, opens
 * {@code file}, asks {@code textDocument/definition} at the 0-based {@code line}/{@code character},
 * shuts the server down and answers the locations with timings.
 * <p>
 * Spike only: it runs the argument vector of an authenticated request, which no product endpoint
 * does.
 */
@jakarta.ws.rs.Path("/api/v1/spike/lsp")
@Authenticated
public class LspSpikeResource {

    static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * The request.
     *
     * @param command    the language server argument vector
     * @param file       the absolute file path; its directory is the workspace
     * @param line       the 0-based line
     * @param character  the 0-based character
     * @param languageId the LSP language id, {@code plaintext} when absent
     */
    public record Request(List<String> command, String file, int line, int character, String languageId) {
    }

    /**
     * @param request the request
     * @return {@code 200} with {@code locations}, or {@code 502} with {@code error} and {@code detail}
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response definition(Request request) {
        if (request == null || request.command() == null || request.command().isEmpty() || request.file() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "command and file required"))
                    .build();
        }
        Path file = Path.of(request.file()).toAbsolutePath();
        long start = System.nanoTime();
        try (LspClient client = LspClient.start(request.command(), file.getParent(), TIMEOUT)) {
            long initialized = System.nanoTime();
            client.open(file, request.languageId() == null ? "plaintext" : request.languageId());
            var locations = new ArrayList<Map<String, Object>>();
            for (LspClient.LspLocation location : client.definition(file, request.line(), request.character())) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("uri", location.uri());
                entry.put("start_line", location.startLine());
                entry.put("start_character", location.startCharacter());
                entry.put("end_line", location.endLine());
                entry.put("end_character", location.endCharacter());
                locations.add(entry);
            }
            long answered = System.nanoTime();
            var body = new LinkedHashMap<String, Object>();
            body.put("locations", locations);
            body.put("position_encoding", client.positionEncoding());
            body.put("initialize_ms", (initialized - start) / 1_000_000);
            body.put("definition_ms", (answered - initialized) / 1_000_000);
            return Response.ok(body).build();
        } catch (LspException e) {
            var body = new LinkedHashMap<String, Object>();
            body.put("error", e.getKind().name());
            body.put("detail", String.valueOf(e.getMessage()));
            return Response.status(Response.Status.BAD_GATEWAY).entity(body).build();
        }
    }
}
