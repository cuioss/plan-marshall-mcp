/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.verify.daemon;

import java.util.Map;
import java.util.TreeMap;


import de.cuioss.pm.mcp.server.ingest.IngestionValidator;
import de.cuioss.pm.mcp.server.security.RequestIdentity;
import io.quarkiverse.mcp.server.ElicitationRequest;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolManager.ToolArguments;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Verification tools of Milestone 0, Part B, registered only with {@code pm.spike.verify=true}:
 * <ul>
 * <li>{@code spike_identity} (gate 10): the security identity a tool handler sees, plus the request's
 * {@code _meta}, so a test can tell whether the connection metadata reaches the handler.</li>
 * <li>{@code spike_progress} (gate 10): three progress notifications on the call's own response stream.</li>
 * <li>{@code spike_elicit} (gate 10): an elicitation, as a server request where the connection supports it,
 * otherwise as an {@code input_required} result (multi round-trip request) answered by a retry.</li>
 * <li>{@code spike_ingest} (gate 1): the ingestion validator, so that {@code commonmark} and {@code cui-http}
 * run inside the native image.</li>
 * </ul>
 */
@ApplicationScoped
public class SpikeVerifyTools {

    static final String CHOICE = "choice";

    private final ToolManager toolManager;
    private final RequestIdentity identity;
    private final boolean active;
    private final IngestionValidator validator = new IngestionValidator();

    SpikeVerifyTools(ToolManager toolManager, RequestIdentity identity,
            @ConfigProperty(name = "pm.spike.verify", defaultValue = "false") boolean active) {
        this.toolManager = toolManager;
        this.identity = identity;
        this.active = active;
    }

    void register(@Observes StartupEvent event) {
        if (!active) {
            return;
        }
        toolManager.newTool("spike_identity").setDescription("Spike: the caller's security identity.")
                .setInputSchema(schema(new JsonObject())).setHandler(this::identity).register();
        toolManager.newTool("spike_progress").setDescription("Spike: three progress notifications.")
                .setInputSchema(schema(new JsonObject())).setHandler(SpikeVerifyTools::progress).register();
        toolManager.newTool("spike_elicit").setDescription("Spike: asks one question by elicitation.")
                .setInputSchema(schema(new JsonObject())).setHandler(SpikeVerifyTools::elicit).register();
        toolManager.newTool("spike_ingest").setDescription("Spike: runs the ingestion validator.")
                .setInputSchema(schema(new JsonObject().put("text", new JsonObject().put("type", "string")
                        .put("description", "Markdown to validate."))).put("required", new JsonArray().add("text")))
                .setHandler(this::ingest).register();
    }

    private static JsonObject schema(JsonObject properties) {
        return new JsonObject().put("type", "object").put("properties", properties).put("additionalProperties",
                false);
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

    private ToolResponse ingest(ToolArguments arguments) {
        var report = validator.validate(String.valueOf(arguments.args().get("text")));
        var findings = new JsonArray();
        report.findings().forEach(finding -> findings.add(new JsonObject().put("kind", finding.kind())
                .put("detail", finding.detail())));
        return ToolResponse.success(new JsonObject().put("refused", report.refused()).put("findings", findings)
                .encode());
    }
}
