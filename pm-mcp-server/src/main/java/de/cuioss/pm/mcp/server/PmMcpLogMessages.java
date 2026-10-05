/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server;

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

        /** Logged at start with the one active credential backend and the keyring service name. */
        public static final LogRecord CREDENTIAL_STORE_SELECTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(30)
                .template("Credential store '%s' active, service name '%s'")
                .build();

        /** Logged when a language server of the LSP pool answered {@code initialize}. */
        public static final LogRecord LSP_STARTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(40)
                .template("Language server '%s' started (pid %s, position encoding %s)")
                .build();
    }

    /** WARN level messages. */
    @UtilityClass
    public static final class WARN {

        /** Logged at start when no OS keyring is usable and the file credential store serves. */
        public static final LogRecord KEYRING_UNAVAILABLE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(130)
                .template("No OS keyring usable, the file credential store serves: %s")
                .build();

        /** Logged when a language server does not end cleanly on {@code shutdown} / {@code exit}. */
        public static final LogRecord LSP_SHUTDOWN_FAILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(140)
                .template("Language server (pid %s) did not end cleanly, terminating it: %s")
                .build();
    }

    /** ERROR level messages. */
    @UtilityClass
    public static final class ERROR {

        /** Logged when a credential store operation of the local API fails; never contains the secret. */
        public static final LogRecord CREDENTIAL_STORE_FAILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(230)
                .template("Credential store '%s' failed: %s")
                .build();
    }
}
