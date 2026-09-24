/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: FSL-1.1-ALv2
 *
 * Licensed under the Functional Source License, Version 1.1, ALv2 Future License
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE.md file at the root of this
 * repository or at https://github.com/cuioss/plan-marshall-mcp/blob/main/LICENSE.md
 */
package de.cuioss.pm.mcp.tools;

import de.cuioss.pm.mcp.PmMcpLogMessages;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

/**
 * Minimal MCP tool proving the tool surface end to end.
 *
 * @since 0.1
 */
public class HelloTool {

    private static final CuiLogger LOGGER = new CuiLogger(HelloTool.class);

    /**
     * Greets the given name.
     *
     * @param name the name to greet
     * @return the greeting
     */
    @Tool(description = "Returns a greeting for the given name.")
    public String hello(@ToolArg(description = "The name to greet") String name) {
        LOGGER.info(PmMcpLogMessages.INFO.HELLO_TOOL_INVOKED, name);
        return "Hello, " + name + "!";
    }
}
