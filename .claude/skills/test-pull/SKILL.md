---
name: test-pull
description: Shared driver of the pull-mechanism verifications V1 to V10 of roadmap Milestone 0, Part A (control direction). Builds and checks the stub, starts and stops runs against Claude Code, OpenCode and Antigravity, reports progress, and turns run data into result rows for doc/concepts/harness-as-worker/record.adoc. Use for setup, selfcheck, smoke, run, status, stop, report and cleanup; kept as the base of the next evaluation (doc/concepts/harness-as-worker/evaluation.adoc).
user-invocable: true
argument-hint: "setup | selfcheck | smoke <harness> | status [run] | stop <run> | report [vN] | cleanup"
allowed-tools: Bash, Read, Edit, Write
---

# test-pull — driver of the pull-mechanism verifications

Throwaway tooling for `doc/roadmap.adoc` Milestone 0, Part A, kept with the package `de.cuioss.pm.mcp.spike` as
the base of the evaluation in `doc/concepts/harness-as-worker/evaluation.adoc`; both are removed when Milestone 1
restructures the modules. The per-verification skills `test-v1` to `test-v10` were removed on 2026-10-03 after
V1 to V10 were recorded: what each verification measures and how to read it is in
`doc/concepts/harness-as-worker/analysis.adoc` and `measurements.adoc`; runs start with `P run vN …`.

## Parts

- **Stub**: `plan-marshall-mcp/src/main/java/de/cuioss/pm/mcp/spike/`. Active only with `-Dpm.spike.scenario=<file>`.
  Tools `pull_wait` (blocks, answers `wait_again`, `task` or `done`), `pull_submit`, `pull_info` (distractor),
  `pull_escalate` (appears mid-run for the widening test). Every observation goes to `events.jsonl`.
- **Driver**: `scripts/pull.py` (stdlib Python). One stub process and one detached supervisor per run.
- **Relay**: `scripts/relay.py`, the stdio server a host starts; forwards to the stub and logs the host side.
- **Scenarios and prompts**: `scripts/scenarios.py`. **Harness adapters**: `scripts/harness.py`.
  **Metrics and verdicts**: `scripts/analyze.py`. **Decision fixtures**: `fixtures/`.
- **Pass criteria**: `criteria.json`, set before the measured runs.
- **Workspaces**: every harness process runs in a directory of its own outside the repository (system temp directory, `pull-spike-ws/<run>`). Inside the repository a harness loads the project instructions and shares the project's persistent memory across sessions. The MCP server is configured under the product's name `plan-marshall`; Claude Code jobs get `ENABLE_TOOL_SEARCH=false` so that the tools are callable without a tool search.
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
| `report [vN]` | `P report [vN]` prints AsciiDoc rows; `--json` prints the raw metrics. Copy rows into `doc/concepts/harness-as-worker/record.adoc` only for measured (non-smoke) runs. |
| `cleanup` | `P cleanup` stops every run and removes the Antigravity server entry. |

## Rules

1. **Criteria first.** A measured run needs `criteria.json` with `"status": "confirmed"`, committed; `P run`
   refuses a run without `--smoke` while the status is `proposed`. Ask the operator to confirm or change
   the criteria; never set the status yourself.
2. **Antigravity changes user configuration.** `agy` has no per-call MCP configuration; a run executes
   `agy mcp add plan-marshall -- <relay command>` and removes the entry when it ends. Show the operator `P consent-agy` and run
   `P consent-agy --yes` only after they agree. Antigravity runs are sequential: `P run … agy …` takes one
   cell or variant per command (`--cell all` and the V8 default of all variants are refused), and the next
   starts when the previous run has ended.
3. **Never repeat a run until it passes.** A run that fails for a tooling reason (stub did not start, harness
   not logged in) is repeated and the repeat is noted. A run that fails its criterion is recorded as failed
   with its failure mode.
4. **Long runs are detached.** `P run` returns at once; follow with `P status <run>`. Do not hold a shell open
   for a two-hour run.
5. **Transport.** A host reaches the stub as it will reach the product: over stdio, through
   `scripts/relay.py`, the stand-in for `pm-mcp serve`, which forwards to the stub over Streamable HTTP.
   `--transport http` connects the host to the stub directly; use it only to compare, and record the
   transport with the value.
6. **Worker identity.** The relay sets the argument `worker` of every `pull_*` call to its worker id
   (`w001`, …; `tui` in an interactive session; `agy` for Antigravity, whose server entry is global), so the
   model never passes it. The relay log `relay-<worker>.jsonl` is the host-side record: requests,
   cancellations, the end of stdin, signals. Over `--transport http` the prompt tells the model its id.
8. **Models.** Headless defaults: `claude-haiku-4-5`, `opencode/space-bunny-free`, `gemini-3.8-flash-medium`.
   When a run with the small model fails its criterion, record the failure and run the verification once
   more with the model of `FALLBACK_MODEL` in `scripts/harness.py` (`--model`); both rows are recorded.
7. **Interactive runs need the operator.** `P run … interactive` prints a checklist (also stored as
   `checklist.txt`): the directory, the launch command, the prompt to paste, and what to do at which cycle.
   Relay it verbatim and collect what the operator saw on screen; the stub cannot see the screen.

## Order of the measured runs

V1 first (its limit sets the wait length of everything else), then V5, V7, V4, then V2 and V10 side by side,
then V3, V6, V8, then V9. Record harness version and model with every result; harness versions move during a
measurement series (`meta.json` keeps both per run).
