---
name: test-v7
description: "Verification V7 of roadmap Milestone 0, Part A: usage per task. Runs the scenario against Claude Code, OpenCode or Antigravity through the pull-mechanism stub and reports the result against the pass criterion."
user-invocable: true
argument-hint: "<claude|opencode|agy>"
allowed-tools: Bash, Read
---

# test-v7 — usage per task

Definition: `doc/concepts/harness-as-worker/analysis.adoc#v7`. Shared rules, verbs and file layout: the skill
`test-pull` (`.claude/skills/test-pull/SKILL.md`); read its rules before the first measured run.

## What it decides

Whether the metrics and the spend check survive a warm worker.

## Scenario

Ten tasks in one session. Every output line of the harness is stamped on arrival, so the usage of the turns between a task's delivery and its submit can be summed.

## Run

```bash
python3 .claude/skills/test-pull/scripts/pull.py run v7 <harness> headless
python3 .claude/skills/test-pull/scripts/pull.py status <run>
python3 .claude/skills/test-pull/scripts/pull.py report v7
```

Add `--smoke` for a short trial run that is never recorded. `--model` overrides the small model of the
harness.

## Operator

None.

## Pass criterion and fallback

- Criterion (`criteria.json`, key `v7`): The output reports usage per turn with all four token components, every task gets its own figures, and the sums reconcile with the run total within 1 %.
- Fallback when it fails: Usage per worker lifetime, apportioned; or W1.

## Reading the report

`per_turn_usage`, `four_components`, `tasks_attributed` of `tasks`, `output_token_reconcile_error`, and `median_task_priced_units` (the marginal cost V5 compares against).

Record the row in `doc/concepts/harness-as-worker/record.adoc` with the criterion, the result per
harness and mode, and the fallback taken.
