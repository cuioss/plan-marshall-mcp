---
name: test-v3
description: "Verification V3 of roadmap Milestone 0, Part A: task loop in one warm session against fresh sessions. Runs the scenario against Claude Code, OpenCode or Antigravity through the pull-mechanism stub and reports the result against the pass criterion."
user-invocable: true
argument-hint: "<claude|opencode|agy> [--fresh] [--rounds N] [--reps N]"
allowed-tools: Bash, Read
---

# test-v3 — task loop in one warm session against fresh sessions

Definition: `doc/concepts/harness-as-worker/analysis.adoc#v3`. Shared rules, verbs and file layout: the skill
`test-pull` (`.claude/skills/test-pull/SKILL.md`); read its rules before the first measured run.

## What it decides

W2 (warm worker per plan) against W1 (fresh job per decision): decision quality over a long session, and the recycle point.

## Scenario

The closed-answer decisions of `fixtures/tasks.json`, cycled for 6 rounds with 8 KB of filler after each task, in one session until the context limit or a compaction. With `--fresh`: every task in a process of its own, 3 repetitions, as the baseline and the noise estimate.

## Run

```bash
python3 .claude/skills/test-pull/scripts/pull.py run v3 <harness> headless
python3 .claude/skills/test-pull/scripts/pull.py run v3 <harness> headless --fresh
python3 .claude/skills/test-pull/scripts/pull.py status <run>
python3 .claude/skills/test-pull/scripts/pull.py report v3
```

Add `--smoke` for a short trial run that is never recorded. `--model` overrides the small model of the
harness.

## Operator

None.

## Pass criterion and fallback

- Criterion (`criteria.json`, key `v3`): Warm accuracy within 5 points of fresh accuracy; the loop continues after a compaction without input.
- Fallback when it fails: Recycle the worker before the compaction point; if quality drops earlier, W1.

## Reading the report

Compare `accuracy_pct` and `last_quarter_pct` of the warm run with `accuracy_pct` of the fresh run. `tasks_before_first_compaction` is the recycle point; `loop_survived_compaction` must be true.

Record the row in `doc/concepts/harness-as-worker/record.adoc` with the criterion, the result per
harness and mode, and the fallback taken.
