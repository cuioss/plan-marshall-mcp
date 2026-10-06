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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The process table ({@code ps -A -o pid=,ppid=,command=}), for the relay of a harness and for orphans, and POSIX
 * signals by number name ({@code kill -STOP}, {@code kill -CONT}).
 */
final class ProcessTree {

    /**
     * One process.
     *
     * @param pid     the pid
     * @param ppid    the parent pid
     * @param command the command line
     */
    record Entry(long pid, long ppid, String command) {

        /** @return whether this is a worker relay {@code pm-mcp serve --job} */
        boolean relay() {
            return (command.contains("pm-mcp serve") || command.contains("PmMcp serve")) && command.contains("--job");
        }
    }

    private ProcessTree() {
    }

    /** @return the process table, empty if {@code ps} fails */
    static List<Entry> table() {
        var entries = new ArrayList<Entry>();
        try {
            var process = new ProcessBuilder("ps", "-A", "-o", "pid=,ppid=,command=").redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(10, TimeUnit.SECONDS);
            for (var line : out.lines().toList()) {
                var parts = line.strip().split("\\s+", 3);
                if (parts.length == 3 && parts[0].chars().allMatch(Character::isDigit)
                        && parts[1].chars().allMatch(Character::isDigit)) {
                    entries.add(new Entry(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2]));
                }
            }
        } catch (IOException _) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return entries;
    }

    /**
     * @param table the process table
     * @param root  a pid
     * @return every descendant of {@code root}
     */
    static List<Entry> descendants(List<Entry> table, long root) {
        var children = new HashMap<Long, List<Entry>>();
        table.forEach(entry -> children.computeIfAbsent(entry.ppid(), _ -> new ArrayList<>()).add(entry));
        var found = new ArrayList<Entry>();
        var queue = new ArrayList<Long>(List.of(root));
        while (!queue.isEmpty()) {
            var pid = queue.removeFirst();
            for (var child : children.getOrDefault(pid, List.of())) {
                found.add(child);
                queue.add(child.pid());
            }
        }
        return found;
    }

    /**
     * @param root a harness pid
     * @return the pids of its worker relays
     */
    static List<Long> relays(long root) {
        return descendants(table(), root).stream().filter(Entry::relay).map(Entry::pid).toList();
    }

    /**
     * @param pids pids to look for
     * @return the ones still running, with their command lines
     */
    static Map<Long, String> alive(List<Long> pids) {
        var alive = new HashMap<Long, String>();
        for (var entry : table()) {
            if (pids.contains(entry.pid())) {
                alive.put(entry.pid(), entry.command());
            }
        }
        return alive;
    }

    /**
     * @param pid    the process
     * @param signal the signal name, e.g. {@code STOP}, {@code CONT}, {@code KILL}, {@code TERM}
     * @return whether {@code kill} succeeded
     */
    static boolean signal(long pid, String signal) {
        try {
            var process = new ProcessBuilder("kill", "-" + signal, Long.toString(pid)).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException _) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
