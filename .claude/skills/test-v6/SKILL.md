---
name: test-v6
description: "Verification V6 of roadmap Milestone 0, Part A: idle cost of a warm session. Runs the scenario against Claude Code, OpenCode or Antigravity through the pull-mechanism stub and reports the result against the pass criterion."
user-invocable: true
argument-hint: "<claude|opencode|agy>"
allowed-tools: Bash, Read
---

# test-v6 — idle cost of a warm session

Definition: `doc/discussions/control-direction.adoc#v6`. Shared rules, verbs and file layout: the skill
`test-pull` (`.claude/skills/test-pull/SKILL.md`); read its rules before the first measured run.

## What it decides

The idle timeout after which a worker ends instead of waiting.

## Scenario

The context is filled to about 5k, 25k, 50k and 100k tokens; at each size 10 idle cycles of 30 s (below the provider cache lifetime) and 3 of 330 s (above it). About 90 minutes.

## Run

```bash
python3 .claude/skills/test-pull/scripts/pull.py run v6 <harness> headless
python3 .claude/skills/test-pull/scripts/pull.py status <run>
python3 .claude/skills/test-pull/scripts/pull.py report v6
```

Add `--smoke` for a short trial run that is never recorded. `--model` overrides the small model of the
harness.

## Operator

None.

## Pass criterion and fallback

- Criterion (`criteria.json`, key `v6`): Recorded. The idle timeout is the point where the accumulated idle cost equals one cold start (V5).
- Fallback when it fails: None.

## Reading the report

`idle_cost`: median priced units per idle cycle by context size and by wait length. Needs per-turn usage (V7); without it the result is `incomplete`.

Record the row in `doc/discussions/control-direction-measurements.adoc` with the criterion, the result per
harness and mode, and the fallback taken.
