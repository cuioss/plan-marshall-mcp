/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime;

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

        // Local adapter and MCP surface: identifiers 10-29

        /** Logged when the socket is bound, secured and recorded (startup step 7). */
        public static final LogRecord RUNTIME_READY = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(10)
                .template("Runtime ready on socket '%s' (pid %s)")
                .build();

        /** Logged when the web listener has opened. */
        public static final LogRecord WEB_LISTENER_OPENED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(11)
                .template("Web listener open on %s:%s (lan: %s)")
                .build();

        /** Logged when the web listener has closed. */
        public static final LogRecord WEB_LISTENER_CLOSED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(12)
                .template("Web listener on port %s closed")
                .build();

        /** Logged when a local client asks the runtime to stop. */
        public static final LogRecord RUNTIME_STOP_REQUESTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(13)
                .template("Runtime stop requested through the local API")
                .build();

        /** Logged when the core tool surface is registered. */
        public static final LogRecord CORE_TOOLS_REGISTERED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(14)
                .template("Core tools registered: %s")
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

        // Local adapter and MCP surface: identifiers 110-129

        /** Logged when the socket did not have mode 0600 after the bind and was restricted. */
        public static final LogRecord SOCKET_MODE_RESTRICTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(110)
                .template("Socket '%s' had mode %s after the bind and was set to 0600")
                .build();

        /** Logged when a core tool is not registered because another component registered the name. */
        public static final LogRecord CORE_TOOL_SHADOWED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(111)
                .template("Core tool '%s' not registered: the name is already registered")
                .build();

        /** Logged when the web listener cannot open. */
        public static final LogRecord WEB_LISTENER_FAILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(112)
                .template("Web listener could not open on port %s: %s")
                .build();

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

        // Local adapter and MCP surface: identifiers 210-229

        /** Logged when the bound socket cannot be secured or recorded; the runtime exits with code 77. */
        public static final LogRecord SOCKET_NOT_SECURED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(210)
                .template("Socket '%s' could not be secured or recorded, exiting with code 77")
                .build();

        /** Logged when a credential store operation of the local API fails; never contains the secret. */
        public static final LogRecord CREDENTIAL_STORE_FAILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(230)
                .template("Credential store '%s' failed: %s")
                .build();
    }
}
