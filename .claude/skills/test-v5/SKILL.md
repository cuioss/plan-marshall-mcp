---
name: test-v5
description: "Verification V5 of roadmap Milestone 0, Part A: cost of a cold start. Runs the scenario against Claude Code, OpenCode or Antigravity through the pull-mechanism stub and reports the result against the pass criterion."
user-invocable: true
argument-hint: "<claude|opencode|agy> [cell|all]"
allowed-tools: Bash, Read
---

# test-v5 — cost of a cold start

Definition: `doc/concepts/harness-as-worker/analysis.adoc#v5`. Shared rules, verbs and file layout: the skill
`test-pull` (`.claude/skills/test-pull/SKILL.md`); read its rules before the first measured run.

## What it decides

Whether warm workers are needed, or fresh jobs are cheap enough.

## Scenario

One minimal decision per fresh process, 10 processes per cell. Cold cells wait 330 s before every process so that a provider cache with a lifetime of five minutes has expired (Claude Code writes its cache entries with a lifetime of one hour: use `--gap 3700 --reps 3` there, and run nothing else on that model meanwhile); `representative` cells append 30 KB of reference text to the prompt.

## Cells

`warm-minimal`, `cold-minimal`, `warm-representative`, `cold-representative`. A cold cell takes about an hour.

## Run

```bash
python3 .claude/skills/test-pull/scripts/pull.py run v5 <harness> headless --cell all
python3 .claude/skills/test-pull/scripts/pull.py status <run>
python3 .claude/skills/test-pull/scripts/pull.py report v5
```

Antigravity (`agy`) takes one `--cell <cell>` per command, each after the previous run ended.

Add `--smoke` for a short trial run that is never recorded. `--model` overrides the small model of the
harness.

## Operator

None.

## Pass criterion and fallback

- Criterion (`criteria.json`, key `v5`): Recorded. Fresh jobs count as cheap enough when the median wall time is ≤ 15 s and the median priced units are ≤ 3 × the marginal priced units of one task in a warm session (V7, `median_task_priced_units`).
- Fallback when it fails: A warm worker is needed.

## Reading the report

`p50_wall_s`, `p50_to_first_contact_s` (process start and MCP connection), `p50_to_submit_s`, `p50_priced_units`, and the cache columns, which show whether a cell really was cold.

Record the row in `doc/concepts/harness-as-worker/record.adoc` with the criterion, the result per
harness and mode, and the fallback taken.
