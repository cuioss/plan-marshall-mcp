/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.api.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("HostDetection")
class HostDetectionTest {

    private static Map<String, String> env(String... pairs) {
        var map = new HashMap<String, String>();
        for (var i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"ANTIGRAVITY_AGENT, antigravity", "OPENCODE, opencode", "OPENCODE_PID, opencode",
            "CLAUDE_CODE_SESSION_ID, claude", "CODEX_SANDBOX, codex", "CODEX_CI, codex", "CODEX_THREAD_ID, codex",
            "UNRELATED, unknown"})
    @DisplayName("detects a host from its primary signal")
    void primary(String variable, String client) {
        assertEquals(client, HostDetection.detect(env(variable, "1")));
    }

    @Test
    @DisplayName("applies the fixed precedence antigravity -> opencode -> claude -> codex")
    void precedence() {
        assertEquals("antigravity", HostDetection.detect(env("CLAUDE_CODE_SESSION_ID", "s", "ANTIGRAVITY_AGENT", "1")));
        assertEquals("opencode", HostDetection.detect(env("CLAUDE_CODE_SESSION_ID", "s", "OPENCODE", "1")));
        assertEquals("claude", HostDetection.detect(env("CLAUDE_CODE_SESSION_ID", "s", "CODEX_CI", "1")));
    }

    @Test
    @DisplayName("consults secondary signals only when no primary signal matched")
    void secondary() {
        assertEquals("claude", HostDetection.detect(env("CLAUDECODE", "1")));
        assertEquals("unknown", HostDetection.detect(env("CLAUDECODE", "0")));
        assertEquals("opencode", HostDetection.detect(env("CLAUDECODE", "1", "OPENCODE", "1")));
    }

    @Test
    @DisplayName("reports an agent environment for mapped and unmapped signals")
    void agentSignal() {
        assertTrue(HostDetection.agentSignalPresent(env("AI_AGENT", "x")));
        assertTrue(HostDetection.agentSignalPresent(env("OPENCODE", "1")));
        assertFalse(HostDetection.agentSignalPresent(env("HOME", "/h")));
    }

    @Test
    @DisplayName("reads the host session identifier from the session ID variable")
    void sessionId() {
        var claude = HostDetection.host("claude").orElseThrow();
        var opencode = HostDetection.host("opencode").orElseThrow();

        assertEquals(Optional.of("abc"), claude.sessionId(env("CLAUDE_CODE_SESSION_ID", "abc")));
        assertEquals(Optional.empty(), claude.sessionId(env("CLAUDE_CODE_SESSION_ID", " ")));
        assertEquals(Optional.empty(), opencode.sessionId(env("OPENCODE", "1")));
        assertEquals(Optional.of("c-1"), HostDetection.host("antigravity").orElseThrow()
                .sessionId(env("ANTIGRAVITY_CONVERSATION_ID", "c-1")));
    }

    @Test
    @DisplayName("carries the version probes and the client ids")
    void probes() {
        assertEquals(List.of("claude", "--version"), HostDetection.host("claude").orElseThrow().versionProbe());
        assertEquals(List.of(), HostDetection.host("antigravity").orElseThrow().versionProbe());
        assertTrue(HostDetection.host("codex").orElseThrow().provisional());
        assertEquals(Optional.empty(), HostDetection.host(HostDetection.NEUTRAL));
        assertEquals(List.of("antigravity", "opencode", "claude", "codex", "neutral"), HostDetection.clientIds());
    }
}
