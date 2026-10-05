/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketPermission;
import java.util.Map;
import java.util.Optional;

import io.quarkus.security.credential.Credential;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Token authentication: header parsing and connection metadata")
class TokenAuthenticationMechanismTest {

    @ParameterizedTest(name = "''{0}'' -> ''{1}''")
    @CsvSource(nullValues = "NONE", value = {"Bearer abc,abc", "bearer  abc ,abc", "Basic abc,NONE", "Bearer ,NONE",
            "NONE,NONE"})
    @DisplayName("extracts the Bearer value")
    void shouldParseBearer(String header, String expected) {
        assertEquals(Optional.ofNullable(expected), TokenAuthenticationMechanism.bearer(header));
    }

    @Test
    @DisplayName("accepts a job token on /mcp only")
    void shouldRecognizeMcpPath() {
        assertTrue(TokenAuthenticationMechanism.isMcp("/mcp"));
        assertTrue(TokenAuthenticationMechanism.isMcp("/mcp/x"));
        assertFalse(TokenAuthenticationMechanism.isMcp("/mcpx"));
        assertFalse(TokenAuthenticationMechanism.isMcp("/api/v1/status"));
        assertFalse(TokenAuthenticationMechanism.isMcp(null));
    }

    @Test
    @DisplayName("maps the relay headers onto identity attributes; a worker keeps only its own")
    void shouldMapConnectionMetadata() {
        var headers = MultiMap.caseInsensitiveMultiMap()
                .add("PM-MCP-Client", "claude")
                .add("PM-MCP-Workspace", "/repo")
                .add("PM-MCP-Generation", "sg-1")
                .add("PM-MCP-Relay-Version", "0.1")
                .add("PM-MCP-Host-Marker", "x".repeat(IdentityAttributes.MAX_VALUE_LENGTH + 1));

        var session = IdentityAttributes.connectionMetadata(headers, CredentialKind.RUNTIME);
        var worker = IdentityAttributes.connectionMetadata(headers, CredentialKind.JOB);

        assertEquals("claude", session.get("pm.client"));
        assertEquals("/repo", session.get("pm.workspace"));
        assertEquals("runtime", session.get(IdentityAttributes.CREDENTIAL));
        assertFalse(session.containsKey("pm.host_marker"));
        assertFalse(worker.containsKey("pm.client"));
        assertFalse(worker.containsKey("pm.workspace"));
        assertEquals("sg-1", worker.get("pm.generation"));
        assertEquals("job", worker.get(IdentityAttributes.CREDENTIAL));
    }

    @Test
    @DisplayName("the identity carries no roles or permissions")
    void shouldBeAuthenticatedOnly() {
        var identity = new PmIdentity(CredentialKind.DEVICE, "d-1", Map.of("pm.device_id", "d-1"));

        assertEquals("d-1", identity.getPrincipal().getName());
        assertFalse(identity.isAnonymous());
        assertTrue(identity.getRoles().isEmpty());
        assertFalse(identity.hasRole("operator"));
        assertTrue(identity.getPermissions().isEmpty());
        assertTrue(identity.getCredentials().isEmpty());
        assertNull(identity.getCredential(Credential.class));
        assertEquals("d-1", identity.<String>getAttribute("pm.device_id"));
        assertFalse(identity.checkPermission(new SocketPermission("localhost", "connect")).await().indefinitely());
    }
}
