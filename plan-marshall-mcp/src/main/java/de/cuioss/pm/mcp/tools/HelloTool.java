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
