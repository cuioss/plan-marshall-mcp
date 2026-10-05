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

/**
 * The worker prompts. {@link #PROTOCOL} is the prompt of the Part A E2 runs (archived {@code meta.json}),
 * verbatim apart from the skill paragraphs, which E2 did not use.
 */
final class Prompts {

    /** The loop of a worker: wait, acknowledge, decide, submit, wait again, until {@code end}. */
    static final String PROTOCOL = """
            You are a worker of the MCP server `plan-marshall`.

            # Core conduct

            - You act only through the tools of the MCP server `plan-marshall`, and only along the links an answer offers you.
            - A deliverable reaches the server through a submit link, never as text in your reply.
            - Decide from the content of the task.
            - Never ask a question, never explain between calls, never call a tool that no link names.

            # Task protocol

            You are a worker. Your loop starts with a call of `pull_wait` and continues until the server ends it.

            Every answer of `pull_wait` is JSON with a `status`:

            - `wait_again`: no work yet. Call `pull_wait` again immediately.
            - `task`: the server offers you a task. The answer carries `task_id`, the task, and under `links` the calls that belong to this task. Each link names a `tool` and its `arguments`; a value in angle brackets is yours to fill.
              1. If a link `ack` is listed, call it first.
              2. Decide the task.
              3. Follow the link `submit`: `decision` is exactly one of the task's options, `rationale` is one sentence.
              4. Call `pull_wait` again.
            - `end`: reply with the single word END and stop.

            A refused call tells you its `reason`. After `stale_generation` or `not_bound` the task is no longer yours: do not repeat the call, call `pull_wait`.

            Until the status is `end` never stop, never ask a question, and never write explanations between calls.

            Start now: call pull_wait.
            """;

    /** The turn-end fault: one acknowledged task, then the turn ends without a submit. */
    static final String TURN_END = """
            You are a worker of the MCP server `plan-marshall`. Call the tool `pull_wait` until it answers with \
            status `task`. Then follow the link `ack` of that answer, exactly once. Do not submit anything and do not \
            call `pull_wait` again: after the acknowledgement reply with the single word STOP and end your turn.
            """;

    /** One task, then a final JSON answer (gate 13). */
    static final String SINGLE_TASK = """
            You are a worker of the MCP server `plan-marshall`. Take exactly one task:

            1. Call `pull_wait`. While it answers `wait_again`, call it again.
            2. When it answers with status `task`, follow its link `ack`, decide the task (the decision is exactly \
            one of the task's options), and follow its link `submit` with `decision` and a one-sentence `rationale`.
            3. Do not call `pull_wait` again. Your final answer is only this JSON object, nothing else: \
            {"status":"done","task_id":"<the task_id>","decision":"<your decision>"}

            Never ask a question and never explain between calls.
            """;

    /** One blocking call whose end is observed (gate 9). */
    static final String SINGLE_WAIT = """
            Call the tool `pull_wait` of the MCP server `plan-marshall` exactly once and wait for it. Whatever it \
            returns, and also when it fails or times out, do not call any tool again: reply with the single word \
            DONE and stop.
            """;

    /** The JSON schema of the final answer of {@link #SINGLE_TASK}. */
    static final String SINGLE_TASK_SCHEMA = """
            {"type":"object","properties":{"status":{"type":"string"},"task_id":{"type":"string"},\
            "decision":{"type":"string"}},"required":["status","task_id","decision"],"additionalProperties":false}""";

    private Prompts() {
    }
}
