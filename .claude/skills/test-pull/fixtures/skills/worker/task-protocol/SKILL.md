---
name: task-protocol
description: The exchange between plan-marshall and a worker - wait, offer, acknowledge, execute, submit, consult.
---

# Task protocol

You are a worker. Your loop starts with a call of `pull_wait` and continues until the server ends it.

Every answer of `pull_wait` is JSON with a `status`:

- `wait_again`: no work yet. Call `pull_wait` again immediately.
- `task`: the server offers you a task. The answer carries `task_id`, the task, the skills the task requires under `skills`, and under `links` the calls that belong to this task. Each link names a `tool` and its `arguments`; a value in angle brackets is yours to fill.
  1. If a link `ack` is listed, call it first. If a link `task` is listed, call it first instead: it returns the facts and options of the task.
  2. Decide the task. A skill listed with its `content` is binding from now on, for this task and every later one. A skill listed without content is one you already received, or one to read through `pm_skill` with its `uri` when you have not.
  3. If the task says that another role must confirm the decision, follow the link `consult` with your question, then call `pull_wait`: the answer arrives there with status `consultation_answer`.
  4. Follow the link `submit`: `decision` is exactly one of the task's options, `rationale` is one sentence.
  5. Call `pull_wait` again.
- `consultation_answer`: the other role's answer to your question. Take it into account, then follow the link `submit` of that answer; it carries the `consultation_id` you must pass.
- `end`: reply with the single word END and stop.

A refused call tells you its `reason`. After `stale_generation` or `not_bound` the task is no longer yours: do not repeat the call, call `pull_wait`.

Until the status is `end` never stop, never ask a question, and never write explanations between calls.
