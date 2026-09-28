---
name: doc-review
description: Adversarial review of the concept documents (requirements, specifications, roadmap, implementation watch) for correctness against a ground-truth repository, completeness, consistency and traceability. Applies fixes that are certain, then walks through every open point with the operator one by one in plain text (issue, where, numbered options, recommendation with rationale), records each decision, applies the decisions in batches through parallel editors, verifies traceability, and commits on request.
user-invocable: true
argument-hint: "[scope or focus] [ground-truth repo path]"
allowed-tools: Agent, Bash, Read, Edit, Write, Grep, Glob
---

# Adversarial Document Review — plan-marshall-mcp

A review is a conversation, not a report. Findings you are certain about are fixed without asking; everything
else is decided by the operator, one point at a time, and nothing is applied on a guess.

## Inputs

- **Scope** (default): `doc/Requirements.adoc` and `doc/Specification.adoc` as entry points, `doc/requirements/`
  and `doc/specification/` as the body, `doc/roadmap.adoc`, `doc/ImplementationWatch.adoc` and
  `doc/implementation-watch/`. An argument may narrow the scope or name a focus topic.
- **Ground truth**: the legacy implementation the concept replaces, default `/Users/oliver/git/plan-marshall`
  (read-only, never edited). An argument may name another path.
- **Traceability rules**: Requirements ↔ Specification bidirectional (every requirement is specified, every
  specification section traces to a requirement, `doc/Specification.adoc` index consistent); both → Implementation
  Watch one-way and optional (a watch item links its anchor; the anchor's "_Implementation watch_" line links back);
  every requirement appears in the roadmap.
- **Operator rules** in `CLAUDE.md` and memory apply throughout (for this repository: clean cut from legacy
  plan-marshall, server-side first, model-facing minimality, native provider modules, no new dependency without the
  operator's approval, no new document without asking, temp files in the session scratchpad).

## Working files

Keep them in the session scratchpad (never in the repository):

- `agenda.md`: every finding with id, status (`certain` / `open` / `decided` / `applied` / `moved`), the decision
  text, and the moved-to cluster.
- `trace-baseline.txt`: output of `scripts/trace.py` before the first edit.
- `brief-<topic>.md`: the common brief handed to editor agents (see Phase 6).
- `<topic>-decisions.md`: running list of decisions of a follow-up round, written after every answer.

Persist progress in memory as well (a project memory `review-open-topics` with: what is decided, what is applied,
what is next, last commit). Update it after every decision; when the operator asks whether you are prepared for a
context compaction, make sure memory and the working files hold everything needed to continue.

## Phase 0 — Setup

1. Confirm the branch is a feature branch (never `main`); create one only if the operator asks for commits.
2. Run `python3 .claude/skills/doc-review/scripts/trace.py > <scratchpad>/trace-baseline.txt`. Known pre-existing
   findings stay in the baseline; after every edit batch only *new* findings count.
3. Create `agenda.md`.

## Phase 1 — Adversarial review

Launch parallel reviewer agents (read-only; `general-purpose` or `Explore`), one per document cluster, e.g.:
(a) requirements modules + `Requirements.adoc`; (b) workflow/DSL/state/phase specifications; (c) runtime, job,
store, filesystem, security specifications; (d) tools, client, dispatch, domain specifications; (e) roadmap +
implementation watch + indexes. Give every reviewer the same instructions:

- Be adversarial: assume defects exist. Check **correctness** against the ground-truth repository (cite
  `file:line` in both repositories; a claim about legacy behaviour without evidence is a finding),
  **completeness** (unspecified behaviour, unrouted outcomes, "to be specified" / "open" markers, undefined terms,
  values without a single source), **consistency** (contradicting values, names, enums, counts, lifecycles between
  documents), **traceability** (rules above).
- Report every finding as: id, location(s), evidence, category, severity, and either the single certain fix or the
  realistic options.
- Do not edit files.

## Phase 2 — Verify and triage

Verify every reported finding yourself before using it (reviewers are wrong often enough). Then classify:

- **certain** — exactly one correct resolution follows from the documents, the ground truth, or an existing
  operator decision (a typo, a broken link, a contradiction where one side is already decided, a missing backlink).
- **open** — more than one defensible resolution, a design choice, a value without evidence, or anything that
  changes behaviour or guarantees. When in doubt, it is open.
- **cluster** — several open points belonging to one larger redesign the operator wants to handle as a topic
  (collect them under a `Zn` heading and handle them in Phase 5).

Number open points `D1, D2, …` in a sensible order (dependencies first, then severity).

## Phase 3 — Apply certain fixes

Apply certain fixes through editor agents (see Phase 6 for the brief and rules) or directly for small ones. Run
`trace.py` and compare with the baseline. Report the applied fixes to the operator in a short list before starting
the discussion.

## Phase 4 — One-by-one discussion (the core of this skill)

Present **exactly one open point per message**, in plain text — never `AskUserQuestion` or any other control.
Use this shape:

```
## D<n>: <short title>

**The issue.** <what is wrong or undecided, concretely, with the relevant facts and evidence>

**Where.** <documents / sections / requirement IDs affected>

**Options.**

1. **<name>** — <what it means, consequences, cost>
2. **<name>** — …
3. …

**Recommendation: <n>.** <rationale: why this option, why not the others, in terms of the project's principles>

What's your decision on D<n>?
```

Rules for the loop:

- Give only realistic options (2–4). Include the option the operator would plausibly choose even if you would not
  recommend it. Keep the recommendation honest; say so when options are close.
- Wait for the answer. Never assume the next answer, never batch several decisions into one question unless the
  operator asks for it.
- Interpreting answers:
  - A number or letter selects that option; "rec", "as recommended", "yes" (to a single recommendation) select the
    recommendation.
  - A free-text answer is a new or modified option: restate in one sentence how you will record it, then record it.
  - If the answer is ambiguous, answers only part of a two-part question, or could refer to another item (for
    example "1" after a message that asked about two things, or "next"), ask one short clarifying question before
    recording anything.
  - If the operator questions the problem itself ("I don't understand the issue"), explain it with a concrete
    example before re-asking.
- When the operator brings a new idea mid-item, evaluate it honestly (what it simplifies, what it costs, which
  earlier decisions it changes, any risk it opens), propose how to adopt it, and ask explicitly whether to adopt.
  After adoption, rework the affected items and mark earlier decisions as adjusted.
- When the operator accepts a risk or defers an improvement "for now", record it as an `L-n` entry in
  `doc/later-improvements.adoc` (deferring decision, accepted limitation, `[ ]` tasks).
- After each answer: one confirmation line ("D<n> recorded: …"), update `agenda.md`, the decision line in the
  proposal document if there is one, and memory; then present the next point.
- New dependencies: name them with licence and purpose inside the options and get explicit approval; record it in
  the decision.

## Phase 5 — Topics that need a redesign

When the operator gives a direction for a larger topic (or asks to handle a cluster later):

1. Research with parallel agents: the current design inventory in the documents (with contradictions), external
   patterns and libraries with sources and versions, feasibility for the stack (Quarkus, GraalVM native).
2. Ask before creating a proposal document, then write `doc/discussions/<topic>.adoc`: status NOTE, operator
   direction, current-design inventory with numbered contradictions, research summary with sources, target
   architecture, and decision items `Zx-n` each with *Question*, *Options*, *Recommendation*, *Affected*. Index it
   in `doc/discussions/README.adoc`.
3. Run the Phase 4 loop over the `Zx-n` items. Record each answer as `* *Decision*: Operator <date>: …` under the
   item (adjustments by later decisions as `* *Adjusted by …*`). When all are decided, mark the document decided.

## Phase 6 — Apply decisions in batches

1. Write a common brief to the scratchpad: the source of truth (proposal document / decisions file — Decision lines
   win over recommendations), a short form of every decision, the project principles, and these editing rules:
   targeted `Edit` replacements only, never whole-file rewrites (editors run in parallel); match surrounding style
   and link forms; keep requirement ↔ specification traceability; new requirement IDs only when genuinely new;
   stay in scope and report cross-scope needs; do not add watch IDs (propose them); run `trace.py` at the end; do
   not commit.
2. Launch 3–4 editor agents in parallel with disjoint scopes (by document or by section of a shared document), each
   told what the others own.
3. Collect the reports. Then launch one reconcile agent: cross-scope needs, naming consistency across files, new
   watch items (next free id per watch document, Anchor/Hazard/Guard/Source, backlinks, counts in
   `ImplementationWatch.adoc` and `Specification.adoc`), and a final `trace.py` against the baseline.
4. Where editors had to choose something the decisions did not settle, check it yourself; if it is not certain,
   present it to the operator as a follow-up point (Phase 4) before or after committing, as the operator prefers.
   Report the remaining editor interpretations in the summary.

## Phase 7 — Summary and commit

- Summarize: what was applied, decisions taken, editor interpretations worth a look, leftovers.
- Commit and push only when the operator asks. Use the repository's commit trailer rule
  (`Co-Authored-By: plan-marshall <noreply@cuioss.de>`), a conventional `docs:` subject, and a body listing the
  decision sets applied. Never push to `main`.

## Scripts

- `scripts/trace.py [DOC_ROOT]` — link/anchor, traceability, index, roadmap-coverage and watch checks (default root
  `<git toplevel>/doc`). Compare every run with the baseline taken in Phase 0.
