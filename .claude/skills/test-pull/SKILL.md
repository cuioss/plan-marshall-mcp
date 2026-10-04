---
name: test-pull
description: Shared driver of the pull-mechanism verifications V1 to V10 of roadmap Milestone 0, Part A (control direction) and of the evaluation E1 to E13 (doc/concepts/harness-as-worker/evaluation.adoc). Builds and checks the stub, starts and stops runs against Claude Code, OpenCode and Antigravity, supervises workers (detection, fencing, recycling, fault injection), reports progress, and turns run data into result rows for doc/concepts/harness-as-worker/record.adoc. Use for setup, selfcheck, smoke, run, status, stop, report and cleanup.
user-invocable: true
argument-hint: "setup | selfcheck | smoke <harness> | status [run] | stop <run> | report [vN|eN] | cleanup"
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

## Evaluation E1 to E13

The items of `doc/concepts/harness-as-worker/evaluation.adoc` are scenarios of this driver: `P run eN <harness>
headless --cell <cell>`, one cell per command (a run takes both model slots).

| Item | Cells | Plan kind |
|---|---|---|
| `e1` acknowledgement | `explicit`, `implicit` | `supervised`: warm pool of two, budget recycle |
| `e2` detection, replacement, fencing | `kill-offer`, `kill-exec`, `stop-short`, `stop-long`, `turn-end`, `relay-kill` | `faults`: one injected fault per trial |
| `e3` idle and budget recycling | `A-norecycle`, `B-recycle`, `budget-fill` | `supervised` |
| `e4` session first | `session-first` (mode `interactive`) | `session_first`: the operator's session beside a headless standby |
| `e5` consultation | `same`, `cross` | `consult`: an asker and a consultant pool, the consultant on the same or the next harness |
| `e6` Antigravity concurrent (harness `agy` only) | `agy2`, `agy4` | `agy_concurrent`: kill trials, identity from the environment |
| `e13` skill delivery | `warm-offer`, `warm-recycle`, `warm-compaction`, `fresh-prompt`, `control`, `uri-switch`, `interactive` (mode `interactive`) | `supervised`, `fresh_skills`, `uri_switch`, `skills_interactive` |

- **Stub switches** (scenario keys, all off for V1 to V10): `supervised` (held waits, generation and role per
  call, fencing), `ack` (`none`, `explicit` with `pull_ack`, `implicit` with `pull_task`),
  `ack_deadline_seconds`, `links`, `consult` (`pull_consult`), `skills_dir` (`pm_skills`, `pm_skill`,
  `pm_skill_file`, resources `skill://pm/…`), `control` (driver tools `spike_state`, `spike_fence`,
  `spike_compacted`, `spike_skill_update`); per task `role`, `release_at_seconds`, `offer_to`,
  `fallback_after_seconds`, `skills`.
- **Job runtime**: `scripts/supervisor.py`, the supervision the product puts into the server. A slot per
  worker, a generation per process (`harness-<worker>g<generation>.jsonl`). It ends a worker on process exit,
  a missed acknowledgement, silence, idle wake-ups, the token budget or a fixed number of submits: SIGTERM,
  SIGKILL after 10 s, `spike_fence`, a successor when the stub shows work. Its record is `supervisor.jsonl`.
- **Relay**: carries `worker`, `generation` and `role` (arguments, or the environment `PM_SPIKE_WORKER`,
  `PM_SPIKE_GENERATION`, `PM_SPIKE_ROLE` when started without `--worker`), hides the `spike_*` tools, and
  answers `skills/list` and `skills/get` from `pm_skills` and `pm_skill`.
- **Prompts are skills**: a worker's launch prompt is the shim paragraph plus the fixture skills
  `fixtures/skills/core` and `fixtures/skills/worker/task-protocol` (`scenarios.protocol_prompt`); the shim
  skill `pm-shim` is installed into the worker directory in each harness's own skill directory.
- **Supervision values**: `fixtures/supervision.json` per harness (bounded wait, progress, ack variant and
  deadline, silence grace, idle wake-ups, token budget). E1 sets the ack values, E2 the silence grace; write
  the measured values there before the items that depend on them.
- **Criteria per stage**: `criteria.json` carries `stage1`, `stage2`, `stage3`; `P run eN` refuses a measured
  run while its stage is `proposed`. Only the operator confirms a stage.
- **Parallelism**: at most two harness processes with a model at a time, so one evaluation run at a time.
  `e6` is the stated exception with four Antigravity jobs.
- **Smoke**: `P run eN <harness> headless --cell <cell> --smoke` for every cell (the interactive cells need
  the operator and print a checklist), then `P report eN --smoke`.
- **Order**: E2 and E1 first (they calibrate the supervision values), then E3, E6, E5, E13, and E4 at a time
  the operator names.

## Order of the measured runs

V1 first (its limit sets the wait length of everything else), then V5, V7, V4, then V2 and V10 side by side,
then V3, V6, V8, then V9. Record harness version and model with every result; harness versions move during a
measurement series (`meta.json` keeps both per run).

## Stage 2 and the verification corpus

- **Roles** are configuration: `fixtures/roles/<set>/<name>.json`, read by `scripts/roles.py`; `P run … --role-set <set>`.
- **E7** reads its cases from the model verification corpus `test/model/verification/` (`scripts/corpus.py`);
  `P judge <run>` scores the open answers against the items' references. `scripts/fixtures.py build` writes
  candidates from archived plans to `.plan/temp/e7-candidates/`; a candidate enters the corpus after review.
- **E8**: `P run e8 claude headless --cell <role-set>`. **E9**: cells `fresh` (form a) and `warm` (form b).

