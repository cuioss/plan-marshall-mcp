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
package de.cuioss.pm.mcp;

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;
import lombok.experimental.UtilityClass;

/**
 * Log messages of the PM-MCP server.
 * <p>
 * Identifier ranges: INFO 001-099, WARN 100-199, ERROR 200-299.
 *
 * @since 0.1
 */
@UtilityClass
public final class PmMcpLogMessages {

    /** Prefix of all PM-MCP log messages. */
    public static final String PREFIX = "PM_MCP";

    /** INFO level messages. */
    @UtilityClass
    public static final class INFO {

        /** Logged when the {@code hello} tool is invoked. */
        public static final LogRecord HELLO_TOOL_INVOKED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(1)
                .template("Hello tool invoked for '%s'")
                .build();
    }
}
