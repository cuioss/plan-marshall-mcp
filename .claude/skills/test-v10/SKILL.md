---
name: test-v10
description: "Verification V10 of roadmap Milestone 0, Part A: the interactive session as puller. Runs the scenario against Claude Code, OpenCode or Antigravity through the pull-mechanism stub and reports the result against the pass criterion."
user-invocable: true
argument-hint: "<claude|opencode|agy>"
allowed-tools: Bash, Read
---

# test-v10 — the interactive session as puller

Definition: `doc/concepts/harness-as-worker/analysis.adoc#v10`. Shared rules, verbs and file layout: the skill
`test-pull` (`.claude/skills/test-pull/SKILL.md`); read its rules before the first measured run.

## What it decides

Option 1 as specified, and the session as a viewer under options 2 and 4.

## Scenario

V2 in the TUI: 200 cycles of 30 s, then 80 cycles whose answers carry about 5k tokens of filler each and force an automatic compaction. The Claude Code workspace carries a `SessionStart` hook that prints the scope pointer (at most 512 bytes) naming `pull_wait` as the next call.

## Run

```bash
python3 .claude/skills/test-pull/scripts/pull.py run v10 <harness> interactive
python3 .claude/skills/test-pull/scripts/pull.py status <run>
python3 .claude/skills/test-pull/scripts/pull.py report v10
```

Add `--smoke` for a short trial run that is never recorded. `--model` overrides the small model of the
harness.

## Operator

Start the TUI with the printed command and paste the prompt. Near cycle 60 type `/clear`; near cycle 120 compact manually; from cycle 200 type nothing. After `/clear` and after the manual compaction note whether the loop resumes by itself, and if not, type the single word `continue` and note whether the first call is `pull_wait`. Note what the screen shows whenever the loop stops.

## Pass criterion and fallback

- Criterion (`criteria.json`, key `v10`): At least 200 cycles and 2 h; the loop continues by itself after the automatic compaction; after `/clear` the first call on the next input is `pull_wait`.
- Fallback when it fails: The session is a viewer only and never the driver.

## Reading the report

`cycles`, `sessions` and `sessionless_requests` (a new session after `/clear`), `max_gap_s` (the pause at `/clear` and at each compaction), `failure_mode`. The operator's notes are part of the result.

Record the row in `doc/concepts/harness-as-worker/record.adoc` with the criterion, the result per
harness and mode, and the fallback taken.
