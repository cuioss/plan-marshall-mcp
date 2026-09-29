---
name: traced-implementation
description: Mandatory frame for every concrete implementation in plan-marshall-mcp. Planning traces each task to its requirements (doc/Requirements.adoc), specification sections (doc/Specification.adoc) and implementation watch items (doc/ImplementationWatch.adoc) and assigns every specified statement its destination (code, tests, concept, developer or user documentation); after implementation, coverage is verified against all three; a requirement found wrong is corrected in the same plan; once verified, the same PR writes the concept, developer and user documentation from the corpus, deletes the implemented specification sections and watch items, and links each requirement to its code, tests and documentation.
user-invocable: true
argument-hint: "[roadmap milestone | specification section | requirement IDs]"
allowed-tools: Agent, Bash, Read, Edit, Write, Grep, Glob
---

# Traced Implementation — plan-marshall-mcp

The specification (`doc/Specification.adoc`, `doc/specification/`) and the implementation watch
(`doc/ImplementationWatch.adoc`, `doc/implementation-watch/`) are scaffolding for code that does not exist
yet. The requirements (`doc/Requirements.adoc`, `doc/requirements/`) are permanent. The specifications carry
far more than the code can express: models, rationale, operating knowledge, configuration, failure
behaviour. When a slice is implemented, that knowledge moves to where its readers look for it:

- **Code and tests**: the normative detail (schemas, enums, grammars, values, outcomes) and the watch guards.
- **Concept documentation**: how and why the system works.
- **Developer documentation**: how the implementation is built, extended and tested.
- **User documentation**: how the system is installed, configured, operated and troubleshot.

An implementation therefore goes through three stages, all inside one PR:

1. **Plan with the trace**: every task names the requirements, specification sections and watch items it
   implements, and every specified statement is assigned its destination.
2. **Implement and verify**: every requirement statement, every normative specification statement and every
   watch item in scope is covered by code and a test, and the documentation for the slice is written against
   the actual code.
3. **Replace**: the implemented specification sections and watch items are deleted, and each requirement
   links to the code, tests and documentation that now carry them.

A PR that implements specified behaviour and leaves the specification or watch text of that behaviour in
place is incomplete. A PR that deletes specification or watch text whose content has not reached its
destination is wrong.

This is the procedure behind `doc/Specification.adoc` § Specification Lifecycle Governance and
`doc/ImplementationWatch.adoc` § Lifecycle: implemented content is removed, not marked (there is no
`IMPLEMENTED` status and no closed-item record). `PLANNED` and `IN PROGRESS` apply to what remains.

Use it together with the plan-marshall workflow (`/plan-marshall`): stage 1 belongs in outline and task
planning (phases 3–4), stage 2 in execution and verification (phase 5), stage 3 before the PR is created
(phase 6).

## Documentation Trees

The documentation is organised by topic and audience, never by milestone or slice. Each tree has an index
document in `doc/` and its topic documents in a folder, like `Requirements.adoc` and `requirements/`:

| Tree | Index | Audience and content |
|---|---|---|
| Concepts | `doc/Concepts.adoc`, `doc/concepts/` | Anyone who needs to understand the system: the runtime and interaction model, the hypermedia state machine, plans, phases, epics, jobs, scopes, stores, the trust and security model, the design decisions and their rationale, lifecycle and invariants. Diagrams belong here. Implementation-neutral where possible. |
| Developer | `doc/DeveloperGuide.adoc`, `doc/developer/` | Contributors: module and package map, the key types and how they collaborate, extension points (a new workflow, provider, domain extension, tool), the test harnesses and patterns, pitfalls (the watch hazards, stated as rules), build and debugging. Links into the code. |
| User | `doc/UserGuide.adoc`, `doc/user/` | Operators and users of a coding agent host: installation and upgrade, project enrolment, client setup per host, CLI commands, configuration keys with defaults and bounds, what the MCP tools do from the user's side, operator decisions and elicitation, error outcomes and troubleshooting. |

- The first slice that needs a tree creates its index and folder; this skill authorises new documents inside
  these three trees (other new documents still need the user's consent, `CLAUDE.md`). The index lists the
  topic documents and is linked from `README.md` and `doc/Requirements.adoc` § Overview.
- A later slice extends the existing topic documents; it creates a new topic document only for a topic that
  has none.
- AsciiDoc, the house style of the existing documents, and small documents (split a topic into parts before
  it grows unwieldy, as the specifications do).
- Every section of a documentation document names the requirements it documents in a line below its
  heading: `_Requirements: link:../Requirements.adoc#PM-TOOL-1[PM-TOOL-1], …_`.
- Reference material is single-sourced: a table that restates values from code (configuration keys,
  defaults, CLI options, outcome enums, log messages) is generated from the code or guarded by a drift test
  that compares it with the code; otherwise the document links the source instead of copying it.
  `doc/LogMessages.adoc` stays the log message reference.

## Inputs

- **Slice**: what is implemented, given as an argument: a roadmap milestone (`doc/roadmap.adoc`), a
  specification document or section, or a list of requirement IDs. Without an argument, ask the user.
- **Traceability checker**: `python3 .claude/skills/doc-review/scripts/trace.py` (broken links and anchors
  across all of `doc/`, requirement ↔ specification links, index, roadmap coverage, watch counts and
  backlinks).
- **Working files**: the session scratchpad (`trace-matrix.md`, `coverage.md`, `trace-baseline.txt`,
  `trace-after.txt`).

## Stage 1 — Plan with the trace

### 1.1 Build the trace matrix

Resolve the slice into three sets, reading every document in full (not by grep excerpts):

- **Requirements**: every `PM-*` requirement the slice implements. A roadmap milestone lists them; a
  specification lists them in its `== Traceability` section. Record each requirement **bullet by bullet**:
  a requirement is often implemented only in part by one slice.
- **Specification sections**: every section (heading with anchor) of `doc/specification/<spec>.adoc` and its
  parts `doc/specification/<spec>/NN-*.adoc` that the slice implements. Also record the sections the slice
  only **depends on** (for example values from `timeouts.adoc`, grammars from `identifiers.adoc`, states from
  `state-catalogue.adoc`): they are read and honoured, but deleted only if the slice implements them.
- **Watch items**, from three sources:
  - the watch document `doc/implementation-watch/<spec>.adoc` of each specification in scope, filtered to
    the items whose *Anchor* lies in the slice;
  - every `doc/implementation-watch/cross-cutting.adoc` (`PM-WATCH-GEN-*`) item anchored to a section or
    requirement in the slice;
  - every item in any other watch document whose *Anchor* names a section or requirement in the slice
    (search all watch documents for the anchors; the _Implementation watch_ lines under the requirement and
    section headings list them).

For every specification section and watch item, assign the **destinations** of its content (usually
several): `code`, `test`, `concept`, `developer`, `user`, each document destination with the target topic
document. A section with no documentation destination states why (for example: an internal detail fully
expressed by a type and its Javadoc). Typical assignments:

- overview, model, state machine, lifecycle, rationale, invariants, diagrams → `concept`;
- module boundary, type collaboration, extension contract, test harness, watch hazard → `developer`
  (hazards as pitfalls: what goes wrong and the rule that prevents it);
- CLI, configuration, installation, enrolment, host setup, operator decision, user-visible outcome,
  failure and recovery → `user`;
- schemas, enums, grammars, values, algorithms → `code` and `test`, plus a reference table in `user` or
  `developer` where readers need it (single-sourced, see above).

Write the matrix to `<scratchpad>/trace-matrix.md`, one row per element:

| Kind | ID / anchor | Link | Statement (short) | Destinations | Task(s) |
|---|---|---|---|---|---|
| REQ | `PM-TOOL-1` bullet 3 | `requirements/04-tools.adoc#PM-TOOL-1` | `pm_state` returns TOON of plan/epic/workspace/entity | code, test | T-2 |
| SPEC | `mcp-tools/01-core-workflow-tools.adoc#_pm_state` | … | closed input schema, … | code, test, concept `concepts/hypermedia.adoc`, user `user/mcp-tools.adoc` | T-2, T-6 |
| WATCH | `PM-WATCH-TOOL-13` | `implementation-watch/mcp-tools.adoc#PM-WATCH-TOOL-13` | guard: … | test, developer `developer/pitfalls.adoc` | T-2, T-7 |

### 1.2 Plan every task with its trace

Every deliverable and task of the plan carries a trace block in its description:

```
Trace:
  Requirements: PM-TOOL-1 (bullets 1, 3), PM-ARCH-4 (bullet 2)
  Specification: mcp-tools/01-core-workflow-tools.adoc#_pm_state, identifiers.adoc#plan-id
  Watch: PM-WATCH-TOOL-1, PM-WATCH-TOOL-13, PM-WATCH-GEN-35
  Guarding tests (planned): PmStateToolTest, PlanIdGrammarTest
  Documentation: concepts/hypermedia.adoc § State representation, user/mcp-tools.adoc § pm_state
```

Rules:

- No task without a trace block; no matrix row without a task. A row the slice deliberately does not cover
  is marked `out of slice` with the reason and stays in the documents.
- Every watch item names the test that will guard it, with its *Fixture* used verbatim.
- Documentation tasks are planned as tasks of their own, ordered after the code tasks they describe, one
  per target topic document (concept, developer, user), each listing the matrix rows it consumes.
- A contradiction between requirement, specification and watch item is resolved by a document correction
  task (§ 1.3) planned ahead of the code tasks it affects, never silently in code.

### 1.3 Correct a requirement that turns out wrong

Implementation is where the documents meet reality: an API does not behave as assumed, a statement is
untestable, two requirements contradict each other, a bound cannot be met, a case is missing. When planning
or implementation detects that a requirement (or a specification section or watch item) is incorrect, the
plan corrects it; the code never diverges from the documents, and the plan does not stop to ask.

- **Add a correction task** to the plan with its own trace block and a `Correction:` field stating the
  finding, the concrete evidence (failing test, API behaviour, measured value, the contradicting statement),
  and the corrected wording. Code tasks that depend on the corrected statement come after it. A finding in
  stage 2 adds the task then; the affected code and documentation tasks are re-run against the corrected
  text.
- **Correct at the source**: edit the requirement bullet in `doc/requirements/NN-*.adoc` itself: keep the
  requirement ID and SMART form ("The system must …", measurable), change only what the evidence shows to be
  wrong. A requirement is never deleted; one that is wholly obsolete is rewritten to what the system must
  actually do.
- **Carry the correction through**: every remaining specification section, watch item, roadmap entry,
  documentation section and other requirement that restates or depends on the corrected statement is
  adapted in the same task, so the documents stay consistent for the slices still to come. The trace matrix
  rows switch to the corrected text.
- **Scope bound**: a correction fixes what this slice proves wrong. A correction that would change the
  product's intent (drop a capability, change a security or trust boundary, change an operator-visible
  contract beyond the slice) is still made in the plan, but flagged as `intent change` in the PR body so
  the reviewer decides on it when approving the PR.
- Every correction is listed in the PR body (§ PR) and is reviewed with the PR.

## Stage 2 — Implement, document, verify

### 2.1 Carry the normative detail into the code

- Schemas, enums, grammars, values, state tables, error outcomes: in code (types, constants, schema
  resources) with Javadoc naming the requirement ID (`PM-TOOL-1`), never a specification or watch anchor.
- Type-local rationale: in the Javadoc of the type or its `package-info.java`; rationale that spans types
  goes to the concept or developer documentation.
- Watch items: the guarding test's Javadoc states the hazard in one or two sentences and uses the fixture
  verbatim, so the test explains itself after the watch document is gone. It names the requirement ID, not
  the `PM-WATCH-*` ID.
- Log messages go to `PmMcpLogMessages` and `doc/LogMessages.adoc` as usual.

### 2.2 Write the documentation from the corpus

After the code tasks, the documentation tasks write the concept, developer and user documentation for the
slice (§ Documentation Trees) from the matrix rows assigned to them:

- **Source**: the specification sections and watch items of the slice, the requirements they trace to, and
  the corrections of § 1.3. Read the relevant `doc/discussions/` documents where the specification cites
  them for rationale.
- **Truth is the code**: the documentation describes what the implementation does, checked against the code
  and tests, not the specification's target. Where they differ, either the code is wrong (fix it) or the
  specification was (correct it, § 1.3); the documentation never states the unimplemented target. Planned
  but unimplemented behaviour is not documented.
- **Rewritten for the reader**: a specification states contracts for implementers; each tree has its own
  audience. Restructure, shorten and explain; do not paste specification prose. Keep diagrams (ASCII or SVG,
  per `pm-documents:ref-ascii-diagrams` / `pm-documents:ref-svg-diagrams`) and adapt them to the implemented
  state.
- **Examples are real**: CLI invocations, configuration snippets and outputs are taken from running the
  implementation (tests or the packaged application), not invented.
- **Extend, don't fork**: add to the existing topic document of the tree; each section carries its
  `_Requirements:_` line.

### 2.3 Verify coverage

Build `<scratchpad>/coverage.md` from the matrix. For every row:

| Kind | ID / anchor | Covered by (main) | Verified by (test) | Documented in | Status |
|---|---|---|---|---|---|

- **REQ** bullet: the class(es) that implement it and a test that asserts the observable behaviour.
- **SPEC** section: each normative statement (every "must", every schema field, enum value, grammar rule,
  edge case, failure outcome) maps to code and a test. Check statement by statement, not section by section.
  Every documentation destination of the row names the document section that now carries it.
- **WATCH** item: the guard is implemented, the named test exists, fails without the guard (state how that
  was checked), and uses the fixture; its documentation destination (usually a developer pitfall) exists.
- **Documentation**: every statement in the new or changed documentation sections agrees with the code
  (read the code for each claim), every reference table is generated or drift-tested, every example was
  produced by the implementation.

Status is `covered`, `gap` or `out of slice`. Use parallel read-only agents for large slices (one per
specification document or documentation tree), then check their tables yourself against the code and the
documents; an agent's "covered" without file, test and document section names does not count.

Then run the build gates of `CLAUDE.md` (quality gate, full verify, coverage, integration tests where the
module has them). Every gate green and every row `covered` or `out of slice` is the precondition for
stage 3. A `gap` is implemented or documented, or, with the user's consent, moved to `out of slice`; it is
never removed from the specification or watch.

## Stage 3 — Replace the specification and watch with links to the implementation

Run `python3 .claude/skills/doc-review/scripts/trace.py > <scratchpad>/trace-baseline.txt` first.

### 3.1 Link the requirements to the implementation and its documentation

In the requirement module (`doc/requirements/NN-*.adoc`), each requirement in the slice gets (or extends)
`Implementation:`, `Verified by:` and `Documentation:` lines where its "See the … Specification … for
implementation details." sentence stands. Paths are relative to `doc/` (the modules are included into
`doc/Requirements.adoc`, like the existing `link:specification/…` links):

```
Implementation: link:../pm-mcp-core/src/main/java/de/cuioss/pm/mcp/core/state/StateRenderer.java[StateRenderer],
link:../pm-mcp-server/src/main/java/de/cuioss/pm/mcp/server/tool/PmStateTool.java[PmStateTool]

Verified by: link:../pm-mcp-server/src/test/java/de/cuioss/pm/mcp/server/tool/PmStateToolTest.java[PmStateToolTest],
link:../pm-mcp-server/src/test/java/de/cuioss/pm/mcp/server/PmStateIT.java[PmStateIT]

Documentation: link:concepts/hypermedia.adoc#_state_representation[Concepts § State Representation],
link:user/mcp-tools.adoc#_pm_state[User Guide § pm_state]
```

- Link types (classes, test classes), not methods or line numbers; link documentation sections by anchor.
- The "See the … Specification" sentence keeps only the specification links that still point at
  remaining sections; with none left, it is removed.
- The requirement's _Implementation watch_ line loses the removed items; with none left, it is removed.
- A partially implemented requirement keeps its remaining specification and watch links beside the new
  lines.

### 3.2 Delete the implemented specification

- Delete every specification section marked `covered` in the matrix.
- A part `doc/specification/<spec>/NN-*.adoc` left without normative sections is deleted and dropped from
  the index's `== Parts` list. A specification whose parts and sections are all gone is deleted entirely
  (index file and directory), together with its row in `doc/Specification.adoc` § Technical Specification
  Index and the prefix table references in `doc/Requirements.adoc`.
- A specification with remaining sections gets `Status: IN PROGRESS`, and its `== Traceability` section
  keeps only the requirements that still have specified content in it.

### 3.3 Delete the covered watch items

- Delete every watch item marked `covered`. Numbers are never reused or renumbered.
- A `cross-cutting.adoc` item anchored to several specifications loses only the anchors that were
  implemented; it is deleted when no anchor remains, and its guard must be covered for every anchor removed.
- Update the item count in `doc/ImplementationWatch.adoc` § Watch Documents and in the Specification index.
  A watch document left without items is deleted together with its row.

### 3.4 Leave no dangling reference

Search the whole repository (not only `doc/`) for every deleted file, anchor and `PM-WATCH-*` ID:
`doc/roadmap.adoc`, other specification and watch documents, `doc/discussions/`, `doc/later-improvements.adoc`,
`CLAUDE.md`, `README.md`, skills under `.claude/skills/`, and code comments. Each reference is re-pointed to
the documentation section that now carries the content, or to the requirement (in code: the requirement
ID), or removed if it only pointed at the deleted text. In the roadmap, the milestone's delivered items are
checked off and their specification links become requirement or documentation links.

When the last specification document is gone, `doc/Specification.adoc`, `doc/specification/`,
`doc/ImplementationWatch.adoc` and `doc/implementation-watch/` are deleted, and the references to them in
`CLAUDE.md`, `README.md`, `doc/Requirements.adoc` § Overview and the `doc-review` skill are removed. That
final step is confirmed with the user before it is done.

### 3.5 Check the documents

Run `trace.py > <scratchpad>/trace-after.txt` and compare with the baseline. The only permitted new
findings are "requirement without specification" for requirements whose specified content is now fully
implemented and which carry `Implementation:` and `Documentation:` lines. Every broken link or anchor
(including those in the new documentation) is fixed. Verify every `link:../…java[…]` target exists on disk.

## PR

One PR contains code, tests, documentation, requirement links and the deletions. The PR body contains:

- **Slice**: milestone / specification / requirements.
- **Coverage**: the table from `coverage.md` (condensed to one row per requirement, specification section
  and watch item, with its documentation destination).
- **Documentation**: the concept, developer and user documents and sections created or extended.
- **Removed**: the deleted specification sections and files, and the deleted watch items (IDs).
- **Requirement corrections**: per correction the requirement ID, the old and new wording (or a diff
  excerpt), the evidence, the adapted specification/watch/roadmap/documentation places, and the
  `intent change` flag where § 1.3 requires it.
- **Remaining**: the `out of slice` rows that stay in the documents, with reasons.

Follow the Git workflow in `CLAUDE.md` for branch, commit, CI and review comments.

## Rules

- Nothing is deleted from the specification or watch without a `covered` row naming code, test and every
  documentation destination.
- Nothing is lost: after deletion, every statement from the removed text is findable in the requirements,
  the code, the tests or the concept, developer and user documentation.
- The documentation describes the implemented system, verified against the code; never the specification's
  unimplemented target.
- Requirements are never deleted. A requirement that turns out wrong is corrected in the same plan and PR,
  backed by evidence (§ 1.3); code never deviates from the documents.
- Contradictions between the three documents are resolved by a planned correction task, never in code.
- Temporary files go into the session scratchpad.
