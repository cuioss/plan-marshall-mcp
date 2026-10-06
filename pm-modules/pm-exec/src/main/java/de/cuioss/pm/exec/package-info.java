/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
/**
 * The job launcher {@code pm-exec}: a tiny native binary the runtime starts every job through
 * (doc/specification/job-runtime/01-pipeline-and-subprocesses.adoc, Process-Group Creation via the
 * {@code pm-exec} Launcher; PM-SEC-5, PM-TECH-4).
 * <p>
 * The launcher places itself into its own process group ({@code setpgid(0, 0)}), confines itself on
 * Linux ({@code PR_SET_NO_NEW_PRIVS} and a Landlock ruleset, doc/specification/job-runtime/
 * 02-confinement-and-environment.adoc) and replaces itself with the verified job executable through
 * {@code execve}. All native calls go through the Foreign Function &amp; Memory API.
 *
 * <h2>Argument vector</h2>
 *
 * <pre>
 * pm-exec --probe
 * pm-exec [--write &lt;path&gt;]... [--read &lt;path&gt;]... [--deny-read &lt;PM_MCP_BASE&gt;] -- &lt;program&gt; [&lt;arg&gt;...]
 * </pre>
 * <ul>
 * <li>{@code --probe} prints one JSON object to {@code stdout},
 * {@code {"os":"linux|macos","landlock_abi":<n>,"no_new_privs":<b>,"confinement":"<c>"}}, and exits
 * {@code 0}; {@code landlock_abi} is {@code 0} where Landlock is unavailable.</li>
 * <li>{@code --write} adds a path to the Landlock write set (all rights the applied ABI handles).</li>
 * <li>{@code --read} adds a path to the read set beyond the enumerated one: the job-private directory
 * of a worker below {@code <PM_MCP_BASE>/run/jobs/}.</li>
 * <li>{@code --deny-read} names {@code <PM_MCP_BASE>}; the read set is everything except it, built by
 * sibling enumeration ({@link de.cuioss.pm.exec.ReadSet}). It is mandatory on Linux.</li>
 * <li>{@code --} ends the options; the next argument is the absolute path of the program, never
 * searched in {@code PATH}, and the rest is its argument vector.</li>
 * </ul>
 * Every path is absolute. The environment of {@code pm-exec} is passed to the program unchanged: the
 * runtime builds it, the launcher adds nothing.
 *
 * <h2>Launch report</h2>
 * Before {@code execve} the launcher writes exactly one JSON line to {@code stderr}:
 * {@code {"pm_exec":"launched","pid":<n>,"process_started_at":"<iso>","confinement":"<c>",
 * "landlock_abi":<n|null>,"no_new_privs":<b>}}, after {@code setpgid}, so {@code pid} is also the
 * process-group id. {@code stderr} rather than an extra descriptor,
 * because {@code ProcessBuilder} hands a child only {@code stdin}, {@code stdout} and {@code stderr}.
 * A failure writes one line {@code {"pm_exec":"error","stage":"<s>","errno":<n>,"message":"<m>"}}
 * and exits {@code 64} (usage), {@code 126} (confinement or process group could not be applied; the
 * program is never started unconfined where Landlock is available) or {@code 127} ({@code execve}
 * failed).
 *
 * @since 0.1
 */
package de.cuioss.pm.exec;
