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
 * The git contract of PM-MCP and its native variant on JGit.
 * <p>
 * {@link de.planmarshall.provider.git.GitOperations} is the contract: typed inputs and one closed
 * outcome enumeration ({@link de.planmarshall.provider.git.GitOutcome}).
 * {@link de.planmarshall.provider.git.JGitOperations} serves it in process, never starting a process:
 * every repository is opened with {@link de.planmarshall.provider.git.RefusingFS}, which refuses JGit's
 * process entry points (hooks, shell commands, filter drivers) with {@code capability_missing}, and
 * linked worktrees are created, listed and removed natively on JGit primitives, since JGit has no
 * {@code git worktree} command.
 */
package de.planmarshall.provider.git;
