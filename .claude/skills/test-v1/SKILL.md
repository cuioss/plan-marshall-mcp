---
name: test-v1
description: "Verification V1 of roadmap Milestone 0, Part A: blocking time of one MCP tool call. Runs the scenario against Claude Code, OpenCode or Antigravity through the pull-mechanism stub and reports the result against the pass criterion."
user-invocable: true
argument-hint: "<claude|opencode|agy> <headless|interactive> [cell|all]"
allowed-tools: Bash, Read
---

# test-v1 — blocking time of one MCP tool call

Definition: `doc/discussions/control-direction.adoc#v1`. Shared rules, verbs and file layout: the skill
`test-pull` (`.claude/skills/test-pull/SKILL.md`); read its rules before the first measured run.

## What it decides

The bounded wait of every client profile (`connection.bounded_wait_seconds`, default 240, at most `idle_timeout_seconds − 30`) and whether a pull can block for minutes or must cycle; whether `notifications/progress` resets the host's timeout (`progress_resets_timeout`).

## Scenario

One `pull_wait` that the stub holds for 3600 s. The stub itself does not learn that a client dropped a held call, so the limit is the earliest sign that the harness gave the call up: a `notifications/cancelled`, a progress frame that could not be written, the harness reporting the call as ended, the model's next call, or the harness exiting.

## Cells

`default-noprog`, `default-prog`, `raised-noprog`, `raised-prog`: host timeout configuration default or raised (knobs in `scripts/harness.py`, `RAISED`; taken from host documentation and verified by this run), progress frames off or every 5 s. A harness that sends no `progressToken` gets no frames; the report shows `progress_token_sent`.

## Run

```bash
python3 .claude/skills/test-pull/scripts/pull.py run v1 <harness> headless --cell all
python3 .claude/skills/test-pull/scripts/pull.py run v1 <harness> interactive --cell <cell>
python3 .claude/skills/test-pull/scripts/pull.py status <run>
python3 .claude/skills/test-pull/scripts/pull.py report v1
```

Antigravity (`agy`) takes one `--cell <cell>` per command, each after the previous run ended.

Add `--smoke` for a short trial run that is never recorded. `--model` overrides the small model of the
harness.

## Operator

Interactive: one TUI session per cell. Start it with the printed command, paste the prompt, leave it alone for up to an hour, note what the screen shows when the call ends, then stop the run. Antigravity: one cell at a time.

## Pass criterion and fallback

- Criterion (`criteria.json`, key `v1`): Limit with the profile's configuration ≥ 270 s. `progress_resets_timeout` is true when the call with progress outlives twice the limit without progress.
- Fallback when it fails: `bounded_wait_seconds` = limit − 30 for that host; shorter cycles.

## Reading the report

`limit` is the measured abort time or `>= 3600 s`; `sign` names what revealed it. Compare the `-prog` and `-noprog` cells of one configuration for the progress question. The values hold for Streamable HTTP only.

Record the row in `doc/discussions/control-direction-measurements.adoc` with the criterion, the result per
harness and mode, and the fallback taken.
