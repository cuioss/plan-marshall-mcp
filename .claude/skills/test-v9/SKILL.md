---
name: test-v9
description: "Verification V9 of roadmap Milestone 0, Part A: binding under load. Runs the scenario against Claude Code, OpenCode or Antigravity through the pull-mechanism stub and reports the result against the pass criterion."
user-invocable: true
argument-hint: "<claude|opencode|agy> [--reps N]"
allowed-tools: Bash, Read
---

# test-v9 — binding under load

Definition: `doc/discussions/control-direction.adoc#v9`. Shared rules, verbs and file layout: the skill
`test-pull` (`.claude/skills/test-pull/SKILL.md`); read its rules before the first measured run.

## What it decides

The recovery semantics of a task loop.

## Scenario

Two workers pull from one queue through two slots, with writer tasks. Ten times the driver kills a worker (`SIGKILL` of its process group) at the moment a task was delivered to it and starts a replacement. After three deliveries the stub registers `pull_escalate` and announces `tools/list_changed`; one task asks the model to call it.

## Run

```bash
python3 .claude/skills/test-pull/scripts/pull.py run v9 <harness> headless
python3 .claude/skills/test-pull/scripts/pull.py status <run>
python3 .claude/skills/test-pull/scripts/pull.py report v9
```

Add `--smoke` for a short trial run that is never recorded. `--model` overrides the small model of the
harness.

## Operator

None. Antigravity cannot run this as long as its server entry is global: both workers share one configuration; record that.

## Pass criterion and fallback

- Criterion (`criteria.json`, key `v9`): Every killed task is delivered again exactly once and submitted exactly once, a late submit is refused, the added tool is never callable, and never two writer tasks at once.
- Fallback when it fails: Fresh job per task, no moving binding.

## Reading the report

`redelivered_exactly_once` of `trials`, `redelivery_reasons` (`connection_closed` when the host's session ends observably, `lease_expired` when the host calls without a session), `duplicate_submits`, `escalate_calls`, `escalate_probe_answer`, `peak_concurrent_writers`.

Record the row in `doc/discussions/control-direction-measurements.adoc` with the criterion, the result per
harness and mode, and the fallback taken.
