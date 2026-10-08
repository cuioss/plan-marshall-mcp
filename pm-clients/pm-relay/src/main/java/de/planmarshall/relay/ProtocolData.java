/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.relay;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import de.planmarshall.api.json.JsonTree;

/**
 * The host's protocol data (protocol version, {@code clientInfo}, capabilities), recorded from its
 * {@code initialize} or taken from a request's {@code params._meta} (MCP {@code 2026-07-28}
 * sessionless form, keys {@code io.modelcontextprotocol/protocolVersion},
 * {@code io.modelcontextprotocol/clientInfo}, {@code io.modelcontextprotocol/clientCapabilities}),
 * and sent with every forwarded request. A repeated {@code initialize} or {@code server/discover}
 * only records the data again.
 */
final class ProtocolData {

    static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

    /**
     * The values one request carries; each is {@code null} while unknown.
     *
     * @param protocolVersion the protocol version
     * @param clientInfo      {@code clientInfo} as compact ASCII JSON
     * @param capabilities    the client capabilities as compact ASCII JSON
     */
    record Snapshot(String protocolVersion, String clientInfo, String capabilities) {
    }

    private final AtomicReference<Snapshot> recorded = new AtomicReference<>(new Snapshot(null, null, null));

    /**
     * Records the protocol data of an {@code initialize} request.
     *
     * @param params its parameters, may be {@code null}
     */
    void recordInitialize(Map<String, Object> params) {
        if (params != null) {
            record(JsonTree.string(params, "protocolVersion"), params.get("clientInfo"), params.get("capabilities"));
        }
    }

    /**
     * Takes the protocol data of a request's {@code params._meta}, recording what it carries.
     *
     * @param params the request parameters, may be {@code null}
     * @return the data to send with the request
     */
    Snapshot forRequest(Map<String, Object> params) {
        var meta = JsonTree.object(params, "_meta");
        if (meta != null) {
            record(JsonTree.string(meta, META_PROTOCOL_VERSION), meta.get(META_CLIENT_INFO),
                    meta.get(META_CLIENT_CAPABILITIES));
        }
        return recorded.get();
    }

    private void record(String version, Object clientInfo, Object capabilities) {
        var info = clientInfo != null ? JsonTree.writeAscii(clientInfo) : null;
        var caps = capabilities != null ? JsonTree.writeAscii(capabilities) : null;
        recorded.updateAndGet(current -> new Snapshot(version != null ? version : current.protocolVersion(),
                info != null ? info : current.clientInfo(), caps != null ? caps : current.capabilities()));
    }
}
