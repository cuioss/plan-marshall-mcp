/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
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

        /** Logged at start when the pull-mechanism stub of Milestone 0 is active. */
        public static final LogRecord SPIKE_ACTIVE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(2)
                .template("Pull-mechanism stub active with scenario '%s', events in '%s'")
                .build();
    }
}
