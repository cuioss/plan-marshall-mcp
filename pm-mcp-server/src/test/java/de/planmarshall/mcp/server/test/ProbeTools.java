/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.test;

import java.util.Map;
import java.util.TreeMap;


import de.planmarshall.mcp.server.security.RequestIdentity;
import io.quarkiverse.mcp.server.ElicitationRequest;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolManager.ToolArguments;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Probe tools of the {@code @QuarkusTest} application, through which the tests observe what the MCP surface
 * hands to a tool handler:
 * <ul>
 * <li>{@code probe_identity}: the security identity the handler sees, plus the request's {@code _meta}.</li>
 * <li>{@code probe_progress}: three progress notifications on the call's own response stream.</li>
 * <li>{@code probe_elicit}: an elicitation, as a server request where the connection supports it, otherwise as
 * an {@code input_required} result answered by a retry.</li>
 * </ul>
 */
@ApplicationScoped
public class ProbeTools {

    static final String CHOICE = "choice";

    private final ToolManager toolManager;
    private final RequestIdentity identity;

    ProbeTools(ToolManager toolManager, RequestIdentity identity) {
        this.toolManager = toolManager;
        this.identity = identity;
    }

    void register(@Observes StartupEvent event) {
        toolManager.newTool("probe_identity").setDescription("Test probe: the caller's security identity.")
                .setInputSchema(schema()).setHandler(this::identity).register();
        toolManager.newTool("probe_progress").setDescription("Test probe: three progress notifications.")
                .setInputSchema(schema()).setHandler(ProbeTools::progress).register();
        toolManager.newTool("probe_elicit").setDescription("Test probe: asks one question by elicitation.")
                .setInputSchema(schema()).setHandler(ProbeTools::elicit).register();
    }

    private static JsonObject schema() {
        return new JsonObject().put("type", "object").put("properties", new JsonObject())
                .put("additionalProperties", false);
    }

    private ToolResponse identity(ToolArguments arguments) {
        var current = identity.current();
        var attributes = new TreeMap<String, Object>();
        current.ifPresent(found -> found.getAttributes().forEach((key, value) -> attributes.put(key,
                String.valueOf(value))));
        return ToolResponse.success(new JsonObject()
                .put("principal", current.map(found -> found.getPrincipal().getName()).orElse(null))
                .put("attributes", new JsonObject(Map.copyOf(attributes)))
                .put("meta", arguments.meta().asJsonObject())
                .encode());
    }

    private static ToolResponse progress(ToolArguments arguments) {
        var progress = arguments.progress();
        if (progress.token().isEmpty()) {
            return ToolResponse.success("no progress token");
        }
        for (int step = 1; step <= 3; step++) {
            progress.notificationBuilder().setProgress(step).setTotal(3).setMessage("step " + step).build()
                    .sendAndForget();
        }
        return ToolResponse.success("progress sent");
    }

    private static ToolResponse elicit(ToolArguments arguments) {
        var elicitation = arguments.elicitation();
        if (elicitation.isServerInitiatedRequestSupported()) {
            if (!elicitation.isFormModeSupported()) {
                return ToolResponse.success("elicitation not supported by the client");
            }
            var response = request(elicitation.requestBuilder()).sendAndAwait();
            return ToolResponse.success("answer: " + response.content().getString(CHOICE));
        }
        var responses = elicitation.inputResponses();
        if (responses.has(CHOICE)) {
            return ToolResponse.success("answer: " + responses.getElicitationResponse(CHOICE).content()
                    .getString(CHOICE));
        }
        throw elicitation.inputRequired().addElicitationRequest(CHOICE, request(elicitation.requestBuilder()))
                .build();
    }

    private static ElicitationRequest request(ElicitationRequest.Builder builder) {
        return builder.setMessage("Pick a bootstrap source")
                .addSchemaProperty(CHOICE, ElicitationRequest.StringSchema.builder().setRequired(true).build())
                .build();
    }
}
