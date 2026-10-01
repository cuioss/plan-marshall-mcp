---
name: test-pull
description: Shared driver of the pull-mechanism verifications V1 to V10 of roadmap Milestone 0, Part A (control direction). Builds and checks the stub, starts and stops runs against Claude Code, OpenCode and Antigravity, reports progress, and turns run data into result rows for doc/discussions/control-direction-measurements.adoc. Use for setup, selfcheck, smoke, status, stop, report and cleanup; the single verifications are the skills test-v1 to test-v10.
user-invocable: true
argument-hint: "setup | selfcheck | smoke <harness> | status [run] | stop <run> | report [vN] | cleanup"
allowed-tools: Bash, Read, Edit, Write
---

# test-pull — driver of the pull-mechanism verifications

Throwaway tooling for `doc/roadmap.adoc` Milestone 0, Part A. It is removed together with the package
`de.cuioss.pm.mcp.spike` when Milestone 1 restructures the modules.

## Parts

- **Stub**: `plan-marshall-mcp/src/main/java/de/cuioss/pm/mcp/spike/`. Active only with `-Dpm.spike.scenario=<file>`.
  Tools `pull_wait` (blocks, answers `wait_again`, `task` or `done`), `pull_submit`, `pull_info` (distractor),
  `pull_escalate` (appears mid-run for the widening test). Every observation goes to `events.jsonl`.
- **Driver**: `scripts/pull.py` (stdlib Python). One stub process and one detached supervisor per run.
- **Scenarios and prompts**: `scripts/scenarios.py`. **Harness adapters**: `scripts/harness.py`.
  **Metrics and verdicts**: `scripts/analyze.py`. **Decision fixtures**: `fixtures/`.
- **Pass criteria**: `criteria.json`, set before the measured runs.
- **Run data**: `.plan/temp/pull-spike/runs/<run>/` (gitignored): `meta.json`, `scenario.json`, `events.jsonl`,
  `harness-<worker>.jsonl` (every output line stamped on arrival), `result.json`, `checklist.txt`.

All commands run from the repository root. `P` below stands for
`python3 .claude/skills/test-pull/scripts/pull.py`.

## Verbs

| Argument | Do |
|---|---|
| `setup` | Build the runner jar with the canonical Maven command of `CLAUDE.md` and the arguments `package -pl plan-marshall-mcp -am -DskipTests`, then `P setup`. Report harness versions and whether the Antigravity consent exists. |
| `selfcheck` | `P selfcheck` and `P selfcheck --silent` (each holds one call for 3600 s, with and without progress frames; `--seconds N` for a short check). Both must report `ok: true`; otherwise V1 results are not attributable to the harness. |
| `smoke <harness>` | `P run vN <harness> headless --smoke` for every N (cells: `--cell all`; for `agy` one command per cell and per V8 variant, each after the previous run ended), then `P report --smoke`. Shakes out the adapters; smoke runs are never recorded as results. |
| `status [run]` | `P status [run]`. |
| `stop <run>` | `P stop <run>`. |
| `report [vN]` | `P report [vN]` prints AsciiDoc rows; `--json` prints the raw metrics. Copy rows into `doc/discussions/control-direction-measurements.adoc` only for measured (non-smoke) runs. |
| `cleanup` | `P cleanup` stops every run and removes the Antigravity server entry. |

## Rules

1. **Criteria first.** A measured run needs `criteria.json` with `"status": "confirmed"`, committed; `P run`
   refuses a run without `--smoke` while the status is `proposed`. Ask the operator to confirm or change
   the criteria; never set the status yourself.
2. **Antigravity changes user configuration.** `agy` has no per-call MCP configuration; a run executes
   `agy mcp add pullstub <url>` and removes the entry when it ends. Show the operator `P consent-agy` and run
   `P consent-agy --yes` only after they agree. Antigravity runs are sequential: `P run … agy …` takes one
   cell or variant per command (`--cell all` and the V8 default of all variants are refused), and the next
   starts when the previous run has ended.
3. **Never repeat a run until it passes.** A run that fails for a tooling reason (stub did not start, harness
   not logged in) is repeated and the repeat is noted. A run that fails its criterion is recorded as failed
   with its failure mode.
4. **Long runs are detached.** `P run` returns at once; follow with `P status <run>`. Do not hold a shell open
   for a two-hour run.
5. **Transport limit.** Every run measures MCP over Streamable HTTP. Hosts will later see stdio from
   `pm-mcp serve`; state this beside every V1 value.
6. **Worker identity.** Every prompt gives the model a worker id that it passes as the argument `worker`.
   Claude Code calls without a session (each request on a transient connection), so the connection cannot
   identify the caller; a call without the argument falls back to the connection id.
7. **Interactive runs need the operator.** `P run … interactive` prints a checklist (also stored as
   `checklist.txt`): the directory, the launch command, the prompt to paste, and what to do at which cycle.
   Relay it verbatim and collect what the operator saw on screen; the stub cannot see the screen.

## Order of the measured runs

V1 first (its limit sets the wait length of everything else), then V5, V7, V4, then V2 and V10 side by side,
then V3, V6, V8, then V9. Record harness version and model with every result; harness versions move during a
measurement series (`meta.json` keeps both per run).
