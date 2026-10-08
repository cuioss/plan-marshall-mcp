/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.tools;

import java.util.List;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.quarkiverse.mcp.server.http.runtime.HttpInputSchemaGenerator;
import io.quarkiverse.mcp.server.http.runtime.McpParamHeaderMetadata;
import io.quarkiverse.mcp.server.runtime.DefaultSchemaGenerator;
import io.quarkiverse.mcp.server.runtime.McpMetadata;
import io.quarkiverse.mcp.server.runtime.SchemaGeneratorConfigCustomizer;
import io.quarkus.arc.All;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Closes the input schema the MCP server generates for annotated tools ({@code @Tool}): the generated schema
 * gets {@code additionalProperties: false} and loses {@code $schema} and {@code title}, so that every schema in
 * {@code tools/list} keeps the flat-schema constraints (doc/specification/mcp-tools/01-core-workflow-tools.adoc,
 * Architectural Constraints, item 3). Programmatic tools with an explicit schema are not affected.
 *
 * @since 0.1
 */
@Singleton
public class ClosedInputSchemaGenerator extends HttpInputSchemaGenerator {

    /**
     * @param customizers    the schema generator customizers
     * @param objectMapper   the object mapper
     * @param metadata       the MCP metadata
     * @param headerMetadata the parameter header metadata
     */
    public ClosedInputSchemaGenerator(@All List<SchemaGeneratorConfigCustomizer> customizers,
            ObjectMapper objectMapper, McpMetadata metadata, McpParamHeaderMetadata headerMetadata) {
        super(customizers, objectMapper, metadata, headerMetadata);
    }

    @Override
    public InputSchema generate(ToolInfo tool) {
        var schema = new JsonObject(super.generate(tool).asJson());
        schema.remove("$schema");
        schema.remove("title");
        schema.put("additionalProperties", false);
        return new DefaultSchemaGenerator.InputSchemaImpl(schema);
    }
}
