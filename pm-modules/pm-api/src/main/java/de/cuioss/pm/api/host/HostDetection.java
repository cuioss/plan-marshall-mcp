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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * The compiled, data-only host-detection table (Client Profiles Specification, Host Integration
 * Table and Host Identity Probes): per host its environment signals in detection precedence, its
 * session ID variable, and its version probe. It is the single source of these values for both
 * client binaries and the client profiles; profile overrides never touch it.
 * <p>
 * Most values are <em>provisional</em> until the host-behaviour spikes verify them (verification gate
 * 14); each entry records whether it carries a provisional value.
 */
@UtilityClass
public class HostDetection {

    /** The client id of the neutral fallback profile. */
    public static final String NEUTRAL = "neutral";

    /** The detected client when no signal matched. */
    public static final String UNKNOWN = "unknown";

    /**
     * The version pattern applied to a probe's {@code stdout} (first capture group), provisional for
     * every host: the specification names no per-host pattern yet.
     */
    public static final String PROVISIONAL_VERSION_PATTERN = "(\\d+(?:\\.\\d+)+)";

    /** Model source of a host that declares its model identity itself. */
    private static final String DECLARED_MODEL = "declared";

    /** The argument that makes a host binary print its build. */
    private static final String VERSION_FLAG = "--version";

    /**
     * One environment signal.
     *
     * @param variable      the environment variable
     * @param requiredValue the value it must have, {@code null} if presence suffices
     */
    public record Signal(String variable, String requiredValue) {

        /**
         * @param variable      the variable
         * @param requiredValue the required value
         */
        public Signal {
            Objects.requireNonNull(variable, "variable");
        }

        /**
         * @param variable the variable
         * @return a signal matched by the variable's presence
         */
        public static Signal present(String variable) {
            return new Signal(variable, null);
        }

        /**
         * @param environment an environment
         * @return {@code true} if the signal is present in it
         */
        public boolean matches(Map<String, String> environment) {
            var value = environment.get(variable);
            return value != null && (requiredValue == null || requiredValue.equals(value));
        }
    }

    /**
     * One host of the table.
     *
     * @param client            the client id
     * @param primarySignals    the primary detection signals, any one matches
     * @param secondarySignals  signals consulted only when no primary signal of any host matched
     * @param sessionIdVariable the variable holding the host session identifier, {@code null} if none
     * @param versionProbe      the argument vector printing the host build, empty if none
     * @param versionPattern    the pattern over the probe's output, {@code null} without a probe
     * @param modelSource       where the model identity comes from
     * @param provisional       {@code true} if any value of the row is provisional
     */
    public record Host(String client, List<Signal> primarySignals, List<Signal> secondarySignals,
    String sessionIdVariable, List<String> versionProbe, String versionPattern, String modelSource,
    boolean provisional) {

        /**
         * Copies the lists.
         *
         * @param client            the client id
         * @param primarySignals    the primary signals
         * @param secondarySignals  the secondary signals
         * @param sessionIdVariable the session ID variable
         * @param versionProbe      the version probe
         * @param versionPattern    the version pattern
         * @param modelSource       the model source
         * @param provisional       whether a value is provisional
         */
        public Host {
            Objects.requireNonNull(client, "client");
            primarySignals = List.copyOf(primarySignals);
            secondarySignals = List.copyOf(secondarySignals);
            versionProbe = List.copyOf(versionProbe);
        }

        /**
         * @param environment an environment
         * @return the host session identifier, if the host provides one
         */
        public Optional<String> sessionId(Map<String, String> environment) {
            return sessionIdVariable == null ? Optional.empty()
                    : Optional.ofNullable(environment.get(sessionIdVariable)).filter(id -> !id.isBlank());
        }
    }

    /** The hosts in detection precedence: {@code antigravity -> opencode -> claude -> codex}. */
    public static final List<Host> HOSTS = List.of(
            new Host("antigravity", List.of(Signal.present("ANTIGRAVITY_AGENT")), List.of(),
                    "ANTIGRAVITY_CONVERSATION_ID", List.of(), null, DECLARED_MODEL, true),
            new Host("opencode", List.of(Signal.present("OPENCODE"), Signal.present("OPENCODE_PID")), List.of(),
                    null, List.of("opencode", VERSION_FLAG), PROVISIONAL_VERSION_PATTERN, DECLARED_MODEL, true),
            new Host("claude", List.of(Signal.present("CLAUDE_CODE_SESSION_ID")),
                    List.of(new Signal("CLAUDECODE", "1")), "CLAUDE_CODE_SESSION_ID",
                    List.of("claude", VERSION_FLAG), PROVISIONAL_VERSION_PATTERN, DECLARED_MODEL, true),
            new Host("codex", List.of(Signal.present("CODEX_SANDBOX"), Signal.present("CODEX_CI"),
                            Signal.present("CODEX_THREAD_ID")), List.of(), "CODEX_THREAD_ID",
                    List.of("codex", VERSION_FLAG), PROVISIONAL_VERSION_PATTERN, DECLARED_MODEL, true));

    /**
     * Secondary agent signals not yet mapped to a host (provisional; the mapping is a host fact
     * still to verify). They mark a coding-agent environment but name no client.
     */
    public static final List<Signal> UNMAPPED_AGENT_SIGNALS = List.of(Signal.present("AI_AGENT"));

    /**
     * Detects the host from an environment: primary signals in the fixed precedence, then the
     * secondary signals; the first match wins.
     *
     * @param environment the environment
     * @return the detected client id, or {@link #UNKNOWN}
     */
    public static String detect(Map<String, String> environment) {
        for (var host : HOSTS) {
            if (host.primarySignals().stream().anyMatch(signal -> signal.matches(environment))) {
                return host.client();
            }
        }
        for (var host : HOSTS) {
            if (host.secondarySignals().stream().anyMatch(signal -> signal.matches(environment))) {
                return host.client();
            }
        }
        return UNKNOWN;
    }

    /**
     * @param environment the environment
     * @return {@code true} if any coding-agent signal, mapped or not, is present
     */
    public static boolean agentSignalPresent(Map<String, String> environment) {
        return !UNKNOWN.equals(detect(environment))
                || UNMAPPED_AGENT_SIGNALS.stream().anyMatch(signal -> signal.matches(environment));
    }

    /**
     * @param client a client id
     * @return its table row; empty for {@link #NEUTRAL} and unknown ids
     */
    public static Optional<Host> host(String client) {
        return HOSTS.stream().filter(host -> host.client().equals(client)).findFirst();
    }

    /** @return every client id a relay may declare, {@link #NEUTRAL} last */
    public static List<String> clientIds() {
        var ids = new ArrayList<String>();
        HOSTS.forEach(host -> ids.add(host.client()));
        ids.add(NEUTRAL);
        return List.copyOf(ids);
    }
}
