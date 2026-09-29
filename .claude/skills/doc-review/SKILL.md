---
name: doc-review
description: Adversarial review of the concept documents (requirements, specifications, roadmap, implementation watch) for correctness against a ground-truth repository, completeness, consistency and traceability. Applies fixes that are certain, then walks through every open point with the operator one by one in plain text (progress count, background, issue with its concrete damage, where, numbered options minimal-first, recommendation with rationale), records each decision, applies the decisions in batches through parallel editors plus a reconcile pass, keeps specification files small, verifies traceability, and commits on request.
user-invocable: true
argument-hint: "[scope or focus] [ground-truth repo path]"
allowed-tools: Agent, Bash, Read, Edit, Write, Grep, Glob
---

# Adversarial Document Review — plan-marshall-mcp

A review is a conversation, not a report. Findings you are certain about are fixed without asking; everything
else is decided by the operator, one point at a time, and nothing is applied on a guess.

## Inputs

- **Scope** (default): `doc/Requirements.adoc` and `doc/Specification.adoc` as entry points, `doc/requirements/`
  and `doc/specification/` as the body (specifications are split: an index `doc/specification/<spec>.adoc` plus
  parts `doc/specification/<spec>/NN-<topic>.adoc`), `doc/roadmap.adoc`, `doc/ImplementationWatch.adoc` and
  `doc/implementation-watch/`. An argument may narrow the scope or name a focus topic.
- **Ground truth**: the legacy implementation the concept replaces, default `/Users/oliver/git/plan-marshall`
  (read-only, never edited). An argument may name another path.
- **Traceability rules**: Requirements ↔ Specification bidirectional (every requirement is specified, every
  specification section traces to a requirement, `doc/Specification.adoc` index consistent); both → Implementation
  Watch one-way and optional (a watch item links its anchor; the anchor's "_Implementation watch_" line links back);
  every requirement appears in the roadmap.
- **Operator rules** in `CLAUDE.md` and memory apply throughout (for this repository: clean cut from legacy
  plan-marshall, server-side first, model-facing minimality, native provider modules, prefer simple resolutions,
  the process enforces itself (no in-run operator questions, defined fallbacks, deliberate waivers only), no new
  dependency without the operator's approval, no new document without asking, temp files in the session
  scratchpad).
- **File size**: every specification file (index or part) stays at or below 400 lines; up to 450 is acceptable when
  a cut would separate content that belongs together.

## Working files

Keep them in the session scratchpad (never in the repository):

- `agenda.md`: every finding with id, status (`certain` / `open` / `decided` / `applied` / `moved` / `superseded`),
  the decision text, the moved-to cluster, and a running discussion log (one line per answer).
- `trace-baseline.txt`: output of `scripts/trace.py` before the first edit; refreshed after every commit.
- `brief-<topic>.md`: the common brief handed to editor agents (see Phase 6).
- `reconcile-notes.md`: per editor report, its interpretations, cross-scope needs, conflicts with other editors,
  alias anchors it kept, and proposed watch items — the input of the reconcile agent.
- `<topic>-decisions.md` or a decision extract: the compact list of decisions of a round, for the editor brief.

Persist progress in memory as well (a project memory `review-open-topics` with: what is decided, what is applied,
what is next, last commit). Update it after every few decisions and after every batch; when the operator asks
whether you are prepared for a context compaction, make sure memory and the working files hold everything needed
to continue.

## Phase 0 — Setup

1. Confirm the branch is a feature branch (never `main`); create one only if the operator asks for commits.
2. Run `python3 .claude/skills/doc-review/scripts/trace.py > <scratchpad>/trace-baseline.txt`. Before trusting a
   reported defect in the baseline, check it is not a checker false positive (render the anchor with the
   Asciidoctor id rules; `trace.py` implements them, but new AsciiDoc constructs can still trip it). Fix the checker
   rather than the documents when the checker is wrong. Known pre-existing findings stay in the baseline; after
   every edit batch only *new* findings count.
3. Check file sizes (`wc -l doc/specification/**/*.adoc`); if files exceed the size rule, plan a split (Phase 6a)
   before any content edit.
4. Create `agenda.md`.

## Phase 1 — Adversarial review

Launch parallel reviewer agents (read-only; `general-purpose` or `Explore`), one per document cluster, e.g.:
(a) requirements modules + `Requirements.adoc`; (b) workflow/DSL/state/phase specifications; (c) runtime, job,
store, filesystem, security specifications; (d) tools, client, dispatch, domain specifications; (e) roadmap +
implementation watch + indexes + decided discussion documents (every `* *Decision*:` actually applied?). Write
the common reviewer instructions to a brief file and give every reviewer the same brief:

- Be adversarial: assume defects exist. Check **correctness** against the ground-truth repository (cite
  `file:line` in both repositories; a claim about legacy behaviour without evidence is a finding),
  **completeness** (unspecified behaviour, unrouted outcomes, "to be specified" / "open" markers, undefined terms,
  values without a single source), **consistency** (contradicting values, names, enums, counts, lifecycles between
  documents), **traceability** (rules above), **ordering** (a roadmap milestone that uses something delivered
  later).
- List the operator principles and the known checker findings so reviewers neither report decided principles as
  defects nor re-report the baseline.
- Report every finding as: id, location(s), evidence, category, severity, and either the single certain fix or the
  realistic options.
- Do not edit files.

## Phase 2 — Verify and triage

Verify every reported finding yourself before using it (reviewers are wrong often enough; a spot check of a third of
the certain ones is the minimum, and every open one is verified when it is presented). Then classify:

- **certain** — exactly one correct resolution follows from the documents, the ground truth, or an existing
  operator decision (a typo, a broken link, a contradiction where one side is already decided, a missing backlink).
- **open** — more than one defensible resolution, a design choice, a value without evidence, or anything that
  changes behaviour or guarantees. When in doubt, it is open.
- **cluster** — several open points belonging to one larger redesign (collect them under a `Zn` heading and handle
  them in Phase 5).

Merge duplicates reported by several reviewers into one point. Number open points `D1, D2, …` in a sensible order
(dependencies first, then severity).

## Phase 3 — Apply certain fixes

Apply certain fixes through editor agents (see Phase 6 for the brief and rules; tell editors to re-verify each item
first and to skip and report it when the text does not match the claim) or directly for small ones. Run
`trace.py` and compare with the baseline. Report the applied fixes to the operator in a short list before starting
the discussion, including the editor interpretations worth a look.

## Phase 4 — One-by-one discussion (the core of this skill)

Present **exactly one open point per message**, in plain text — never `AskUserQuestion` or any other control.
Use this shape:

```
## D<n>: <short title>   (answered <a> / open <o>)

**Background.** <only when the point is not self-explanatory: what the mechanism is and why it exists, in plain
words, one to three sentences>

**The issue.** <what is wrong or undecided, concretely, with the relevant facts and evidence — and the concrete
damage: what actually breaks, for whom, how often>

**Where.** <documents / sections / requirement IDs affected>

**Options.**

1. **<name>** — <what it means, consequences, cost>
2. **<name>** — …
3. …

**Recommendation: <n>.** <rationale: why this option, why not the others, in terms of the project's principles>

What's your decision on D<n>?
```

The progress count `(answered <a> / open <o>)` is part of every point's heading: `<a>` is the number of points of
the current list already decided (including superseded ones), `<o>` the number still open including the one shown.
The current list is the list being discussed (the `D` list, or the `Zx` items plus their follow-ups of a topic,
or the `F` follow-ups of a batch); when items are added or superseded, the next count reflects it.

Rules for the loop:

- **Minimal first.** Before offering machinery (markers, re-derivation, indexes, new states, periodic sweeps,
  extra status fields), state the concrete damage of the gap. List the minimal option first (a fixed order, one
  sentence that states the consequence, reuse of an existing mechanism, or removal of the feature) and recommend it
  unless a concrete harmful outcome remains. Say so when a point turns out not to be a defect (for example a
  documented behaviour that is merely unreminded).
- Give only realistic options (2–4). Include the option the operator would plausibly choose even if you would not
  recommend it. Keep the recommendation honest; say so when options are close.
- Wait for the answer. Never assume the next answer, never batch several decisions into one question unless the
  operator asks for it or the point is a confirm-set (a table of rows that follow from existing decisions; show the
  table, mark rows adjusted by later decisions, ask once for the set).
- Interpreting answers:
  - A number or letter selects that option; "rec", "as recommended", "as proposed", "yes" (to a single
    recommendation) select the recommendation.
  - A free-text answer is a new or modified option: restate in one or two sentences how you will record it, then
    record it.
  - If the answer is ambiguous, answers only part of a two-part question, or could refer to another item (for
    example "1" after a message that asked about two things, "11" where only 1–3 exist, or "next"), either ask one
    short clarifying question before recording anything, or — when a sensible default is obvious — state the
    assumption you take in one line so the operator can correct it.
  - If the operator questions the problem itself ("I don't understand the issue", "what are we talking about"),
    explain it with a concrete example and the categories involved before re-asking.
- **When the operator questions the framing** ("isn't that over-engineering?", "halting is no process", "this is
  process conformance, not security"), stop presenting items. Re-derive the category of the problem, propose the
  reframed principle, and ask for it explicitly. After a principle-level decision, revisit the decided items for
  over-engineering or contradiction (propose the trims in one message), and revalidate the remaining open items with
  an agent before continuing (each: unchanged / changed with new options / superseded; plus the follow-up items the
  new principle creates).
- When the operator brings a new idea mid-item, evaluate it honestly (what it simplifies, what it costs, which
  earlier decisions it changes, any risk it opens), propose how to adopt it, and ask explicitly whether to adopt.
  After adoption, rework the affected items and add `* *Adjusted by …*` lines to the earlier decisions.
- When the operator accepts a risk or defers an improvement "for now" / "later", record it as an `L-n` entry in
  `doc/later-improvements.adoc` (deferring decision, accepted limitation, `[ ]` tasks).
- After each answer: one confirmation line ("D<n> recorded: …"), record the decision (agenda line; in a proposal
  document the `* *Decision*:` line via `scripts/decide.py`), and update memory regularly; then present the next
  point in the same message.
- New dependencies: name them with licence and purpose inside the options and get explicit approval; record it in
  the decision.

## Phase 5 — Topics that need a redesign

When the operator gives a direction for a larger topic (or asks to handle a cluster later):

1. Ask the direction question first (`Zx-0`): complete the model on paper, cut it back, or a hybrid; the answer
   shapes every item.
2. Research with parallel agents, one per area, each writing draft items in the item shape to the scratchpad: the
   current design inventory (with contradictions and markers), the ground-truth behaviour with `file:line`,
   external patterns and libraries with sources and versions, feasibility for the stack (Quarkus, GraalVM native).
3. Ask before creating a proposal document, then let one agent merge the drafts into `doc/discussions/<topic>.adoc`:
   status NOTE, operator direction, current-design inventory with numbered contradictions, research summary with
   sources, and decision items `Zx-n` each with *Question*, *Evidence*, *Options*, *Recommendation*, *Affected*,
   de-duplicated across drafts (disagreements between drafts become options). Index it in
   `doc/discussions/README.adoc`. Commit the decided proposal separately from the applied changes.
4. Run the Phase 4 loop over the `Zx-n` items. Record each answer as `* *Decision*: Operator <date>: …` under the
   item (`scripts/decide.py`); adjustments by later decisions as `* *Adjusted by …*`; principle-level decisions as
   lettered items (`Zx-A`, `Zx-B`, …) at the top. When all are decided, mark the document decided.

## Phase 6 — Apply decisions in batches

6a. **Split first when needed.** If specification files exceed the size rule, split them mechanically before any
content edit, as its own commit: the index keeps the spec's name (overview, traceability, status, parts list), the
parts go to `doc/specification/<spec>/NN-<topic>.adoc`, cuts only at section boundaries, every link across `doc/` is
rewritten to the part that holds the anchor, `trace.py` must match the baseline exactly, and a content check proves
every line of the original survives.

1. Write a common brief to the scratchpad: the source of truth (proposal document / decisions extract — Decision
   and Adjusted-by lines win over recommendations; superseded items are not applied), a compact form of every
   decision, the project principles, and these editing rules: targeted `Edit` replacements (rewriting a whole part
   is allowed only for a part the editor owns and mostly replaces); match surrounding style and link forms; keep
   requirement ↔ specification traceability; new requirement IDs only when genuinely new; stay in scope and report
   cross-scope needs; do not add watch IDs (propose them); keep every part within the size rule (split into a new
   part and update the index Parts list when it grows); run `trace.py` at the end; report line counts; do not
   commit.
2. Launch editor agents in parallel with disjoint scopes (by document or group of documents; 4–7 for a large
   batch), each told what the others own.
3. Collect every report into `reconcile-notes.md` as it arrives: interpretations, cross-scope needs, names the
   editor introduced, conflicts with other editors' names, alias anchors kept, proposed watch items, and questions
   for the operator. Then launch one reconcile agent with the brief and the notes: resolve naming conflicts, retarget
   links and remove alias anchors (clean cut), sweep the corpus for removed concepts, fix index rows, rewrite
   watch items the decisions contradict (ids are never reused) and add the proposed ones (next free id,
   Anchor/Hazard/Guard/Source, backlinks, counts in `ImplementationWatch.adoc` and `Specification.adoc`), re-check
   sizes, and run `trace.py` against the baseline. Tell it which open operator questions to leave untouched.
4. Points the editors had to choose that the decisions did not settle, and conflicts between two editors'
   readings, become `F-n` follow-up points: present them in the Phase 4 loop (they may run while the reconcile
   agent works), then apply them in one small pass before the commit, with a `Post-apply follow-ups` section in the
   proposal document.

## Phase 7 — Summary and commit

- Summarize: what was applied, decisions taken, editor interpretations worth a look, leftovers, baseline findings
  that remain and why.
- Commit only when the operator asks (a request to commit "when done" covers the batch it was given for). Commit in
  logical steps (checker fixes; certain fixes and decisions; proposal record; mechanical split; applied topic), each
  with the repository's commit trailer rule (`Co-Authored-By: plan-marshall <noreply@cuioss.de>`), a conventional
  `docs:` subject, and a body listing the decision sets applied. Refresh `trace-baseline.txt` after each commit.
  Push only when asked; never push to `main`.

## Scripts

- `scripts/trace.py [DOC_ROOT]` — link/anchor (Asciidoctor id rules), traceability, index, roadmap-coverage, parts
  lists and watch checks (default root `<git toplevel>/doc`). Specification parts map to their spec. Compare every
  run with the baseline taken in Phase 0.
- `scripts/decide.py <proposal.adoc> <item-id> <text>` — records `* *Decision*: Operator <today>: <text>` under the
  item with anchor `[#<item-id>]` (before its `* *Flag*` line, if any); `--adjust <by> <text>` records an
  `* *Adjusted by <by>*:` line instead.
