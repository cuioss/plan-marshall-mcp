/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import io.quarkus.security.Authenticated;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The job tokens of the harness drivers, per worker generation; only while the stub runs a scenario.
 * <ul>
 * <li>{@code POST /api/v1/spike/jobs} {@code {"worker":"w1","generation":2,"role":"worker"}}: {@code 201}
 * {@code {"job_id":"j-w1-g2","token":"pmj_…"}}.</li>
 * <li>{@code DELETE /api/v1/spike/jobs/{jobId}}: revokes the token and fences its generation in the stub, so
 * that its leases are given back at once and every later call of it is refused; {@code 200}
 * {@code {"job_id":…,"worker":…,"generation":…,"released":[…]}}.</li>
 * </ul>
 * Without a scenario, and for an unknown job, the answer is {@code 404}.
 */
@Path("/api/v1/spike/jobs")
@Authenticated
public class SpikeJobResource {

    private static final String JOB_ID = "job_id";

    private final SpikeJobs jobs;
    private final SpikeTools tools;

    /**
     * The request to mint a job token.
     *
     * @param worker     the worker
     * @param generation the generation, at least 1
     * @param role       the role, may be {@code null}
     */
    public record MintRequest(String worker, Integer generation, String role) {
    }

    SpikeJobResource(SpikeJobs jobs, SpikeTools tools) {
        this.jobs = jobs;
        this.tools = tools;
    }

    /**
     * @param request the worker generation
     * @return {@code 201} with the job id and the token
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mint(MintRequest request) {
        if (!tools.isEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (request == null || request.worker() == null || !request.worker().matches("[A-Za-z0-9_.-]{1,64}")
                || request.generation() == null || request.generation() < 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "worker ([A-Za-z0-9_.-]) and generation >= 1 required")).build();
        }
        var minted = jobs.mint(request.worker(), request.generation(), request.role());
        return Response.status(Response.Status.CREATED)
                .entity(Map.of(JOB_ID, minted.job().jobId(), "token", minted.token())).build();
    }

    /**
     * @param jobId the job
     * @return {@code 200} with the leases given back
     */
    @DELETE
    @Path("{jobId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response revoke(@PathParam("jobId") String jobId) {
        if (!tools.isEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        var revoked = jobs.revoke(jobId);
        if (revoked.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        var job = revoked.get();
        var fence = tools.fence(job.worker(), job.generation());
        var body = new LinkedHashMap<String, Object>();
        body.put(JOB_ID, job.jobId());
        body.put("worker", job.worker());
        body.put("generation", job.generation());
        body.put("released", List.copyOf(fence.getJsonArray("released").getList()));
        return Response.ok(body).build();
    }
}
