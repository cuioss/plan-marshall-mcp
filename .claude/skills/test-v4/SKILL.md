---
name: test-v4
description: "Verification V4 of roadmap Milestone 0, Part A: lifetime of a headless session. Runs the scenario against Claude Code, OpenCode or Antigravity through the pull-mechanism stub and reports the result against the pass criterion."
user-invocable: true
argument-hint: "<claude|opencode|agy> [cell|all]"
allowed-tools: Bash, Read
---

# test-v4 — lifetime of a headless session

Definition: `doc/discussions/control-direction.adoc#v4`. Shared rules, verbs and file layout: the skill
`test-pull` (`.claude/skills/test-pull/SKILL.md`); read its rules before the first measured run.

## What it decides

Whether a warm worker (W2) or a resumed session (W4) is technically possible per harness.

## Scenario

Three cells. `sigterm-wait`: `SIGTERM` 20 s into a blocking call. `sigterm-task`: `SIGTERM` at the delivery of a task. `resume`: a session receives a nonce and ends; a second process resumes it by session id and must recall the nonce. Staying alive across long waits and the absence of a turn limit are read from the V2 run (2 h, 240 tool calls in one session).

## Cells

`sigterm-wait`, `sigterm-task`, `resume`.

## Run

```bash
python3 .claude/skills/test-pull/scripts/pull.py run v4 <harness> headless --cell all
python3 .claude/skills/test-pull/scripts/pull.py status <run>
python3 .claude/skills/test-pull/scripts/pull.py report v4
```

Add `--smoke` for a short trial run that is never recorded. `--model` overrides the small model of the
harness.

## Operator

None.

## Pass criterion and fallback

- Criterion (`criteria.json`, key `v4`): Alive for 2 h (V2), exit within 10 s of `SIGTERM` without orphaned processes, the resumed session recalls the nonce.
- Fallback when it fails: W2 or W4 is excluded for that harness.

## Reading the report

`exit_after_s`, `exit_code`, `orphans`, `calls_after_sigterm` (tool calls the dying harness still sent; each one consumes a step of the queue), `result_event_emitted` (whether usage survives a `SIGTERM`), `nonce_recalled`, `resume_wall_s`.

Record the row in `doc/discussions/control-direction-measurements.adoc` with the criterion, the result per
harness and mode, and the fallback taken.
