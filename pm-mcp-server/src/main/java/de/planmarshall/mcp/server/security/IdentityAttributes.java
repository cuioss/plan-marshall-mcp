/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.security;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


import de.planmarshall.runtime.security.CredentialKind;
import io.vertx.core.MultiMap;
import lombok.experimental.UtilityClass;

/**
 * The attribute names of a {@link PmIdentity} and the mapping of the relay's connection-metadata headers onto
 * them (plan-marshall-documentation: doc/specification/runtime-model/03-relay.adoc, Connection Metadata).
 *
 * @since 0.1
 */
@UtilityClass
public final class IdentityAttributes {

    /** The credential kind, lower case. */
    public static final String CREDENTIAL = "pm.credential";
    /** The listener the request arrived on: {@code unix} or {@code web}. */
    public static final String LISTENER = "pm.listener";
    /** Value of {@link #LISTENER} for the Unix domain socket. */
    public static final String LISTENER_UNIX = "unix";
    /** Value of {@link #LISTENER} for the web listener. */
    public static final String LISTENER_WEB = "web";
    /** The job id of a worker connection. */
    public static final String JOB_ID = "pm.job_id";
    /** The device id of a paired browser. */
    public static final String DEVICE_ID = "pm.device_id";

    /** Header of a worker's job token. */
    public static final String JOB_TOKEN_HEADER = "PM-MCP-Job-Token";

    /** Header-to-attribute mapping of the connection metadata of a session relay. */
    static final Map<String, String> SESSION_HEADERS = Map.ofEntries(
            Map.entry("Mcp-Method", "pm.mcp_method"),
            Map.entry("Mcp-Name", "pm.mcp_name"),
            Map.entry("PM-MCP-Relay-Version", "pm.relay_version"),
            Map.entry("PM-MCP-Protocol-Version", "pm.protocol_version"),
            Map.entry("PM-MCP-Client-Info", "pm.client_info"),
            Map.entry("PM-MCP-Client-Capabilities", "pm.client_capabilities"),
            Map.entry("PM-MCP-Client", "pm.client"),
            Map.entry("PM-MCP-Workspace", "pm.workspace"),
            Map.entry("PM-MCP-Generation", "pm.generation"),
            Map.entry("PM-MCP-Host-Marker", "pm.host_marker"));

    /** The headers a worker connection is evaluated by; every other header is ignored. */
    static final Set<String> WORKER_HEADERS = Set.of("Mcp-Method", "Mcp-Name", "PM-MCP-Relay-Version",
            "PM-MCP-Protocol-Version", "PM-MCP-Client-Info", "PM-MCP-Client-Capabilities", "PM-MCP-Generation");

    /** Upper bound of a header value taken into the identity. */
    static final int MAX_VALUE_LENGTH = 4096;

    /**
     * Extracts the connection metadata of a request.
     *
     * @param headers the request headers
     * @param kind    the credential kind; a worker connection keeps only {@link #WORKER_HEADERS}
     * @return the attributes, keyed by attribute name; absent or over-long headers are left out
     */
    public static Map<String, Object> connectionMetadata(MultiMap headers, CredentialKind kind) {
        var attributes = new HashMap<String, Object>();
        for (var entry : SESSION_HEADERS.entrySet()) {
            if (kind == CredentialKind.JOB && !WORKER_HEADERS.contains(entry.getKey())) {
                continue;
            }
            var value = headers.get(entry.getKey());
            if (value != null && value.length() <= MAX_VALUE_LENGTH) {
                attributes.put(entry.getValue(), value);
            }
        }
        attributes.put(CREDENTIAL, kind.name().toLowerCase(Locale.ROOT));
        return attributes;
    }
}
