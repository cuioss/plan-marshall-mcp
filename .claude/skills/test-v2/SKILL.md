---
name: test-v2
description: "Verification V2 of roadmap Milestone 0, Part A: reliability of the wait loop (headless). Runs the scenario against Claude Code, OpenCode or Antigravity through the pull-mechanism stub and reports the result against the pass criterion."
user-invocable: true
argument-hint: "<claude|opencode|agy> [--cycles N] [--wait S]"
allowed-tools: Bash, Read
---

# test-v2 — reliability of the wait loop (headless)

Definition: `doc/concepts/harness-as-worker/analysis.adoc#v2`. Shared rules, verbs and file layout: the skill
`test-pull` (`.claude/skills/test-pull/SKILL.md`); read its rules before the first measured run.

## What it decides

Whether pulling is stable enough to carry a workflow at all, the present design included.

## Scenario

240 answers `wait_again`, each after 30 s (2 h), then `done`. The stub counts cycles, the gap between an answer and the next call, calls of the distractor tool, and aborted calls.

## Run

```bash
python3 .claude/skills/test-pull/scripts/pull.py run v2 <harness> headless
python3 .claude/skills/test-pull/scripts/pull.py status <run>
python3 .claude/skills/test-pull/scripts/pull.py report v2
```

Add `--smoke` for a short trial run that is never recorded. `--model` overrides the small model of the
harness.

## Operator

None. The three harnesses can run in parallel.

## Pass criterion and fallback

- Criterion (`criteria.json`, key `v2`): At least 200 consecutive cycles and 2 h with no failure. Counted failure modes: stops, asks the user, narrates instead of calling, calls another tool.
- Fallback when it fails: The harness is not eligible as puller.

## Reading the report

`failure_mode: None` with `done: True` is a pass. Otherwise `cycles` is the number reached before the failure and `failure_mode` names it, with the model's last text where the harness reports one.

Record the row in `doc/concepts/harness-as-worker/record.adoc` with the criterion, the result per
harness and mode, and the fallback taken.
