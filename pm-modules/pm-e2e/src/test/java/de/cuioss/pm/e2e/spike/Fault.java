/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.e2e.spike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The six faults of the E2 matrix (doc/roadmap/technical_linux.adoc, L11) and the stub event each one is
 * injected at, as in Part A.
 */
enum Fault {

    /** SIGKILL of the harness right after an offer, before its acknowledgement. */
    KILL_OFFER("kill-offer", "offer"),
    /** SIGKILL of the harness right after its acknowledgement, before its submit. */
    KILL_EXEC("kill-exec", "ack"),
    /** SIGSTOP of the harness after its acknowledgement, shorter than the silence limit, then SIGCONT. */
    STOP_SHORT("stop-short", "ack"),
    /** SIGSTOP of the harness after its acknowledgement, longer than the silence limit, then SIGCONT. */
    STOP_LONG("stop-long", "ack"),
    /** A generation started with a prompt that acknowledges one task and ends its turn without a submit. */
    TURN_END("turn-end", null),
    /** SIGKILL of the worker relay {@code pm-mcp serve --job} under a held wait. */
    RELAY_KILL("relay-kill", "wait_start");

    private final String id;
    private final String triggerEvent;

    Fault(String id, String triggerEvent) {
        this.id = id;
        this.triggerEvent = triggerEvent;
    }

    /** @return the id used in flags, logs and result files */
    String id() {
        return id;
    }

    /** @return the stub event the fault is injected at, {@code null} for {@link #TURN_END} */
    String triggerEvent() {
        return triggerEvent;
    }

    /** @return whether the fault stops the harness with SIGSTOP */
    boolean stops() {
        return this == STOP_SHORT || this == STOP_LONG;
    }

    /** @return whether the fault takes a task with it that a successor must submit */
    boolean losesTask() {
        return this == KILL_OFFER || this == KILL_EXEC || this == STOP_LONG || this == TURN_END;
    }

    /**
     * @param id a fault id
     * @return the fault
     * @throws IllegalArgumentException for an unknown id
     */
    static Fault of(String id) {
        var normalized = id.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(fault -> fault.id.equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown fault " + id + "; known: "
                        + Arrays.stream(values()).map(Fault::id).toList()));
    }

    /**
     * @param list {@code all} or a comma-separated list of fault ids
     * @return the faults in the given order
     */
    static List<Fault> parse(String list) {
        if (list == null || list.isBlank() || "all".equalsIgnoreCase(list.strip())) {
            return List.of(values());
        }
        var faults = new ArrayList<Fault>();
        for (var part : list.split(",")) {
            if (!part.isBlank()) {
                faults.add(of(part));
            }
        }
        return List.copyOf(faults);
    }
}
