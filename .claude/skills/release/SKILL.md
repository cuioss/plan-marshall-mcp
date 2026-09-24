---
name: release
description: Cut a plan-marshall-mcp release along one of exactly two paths — merging a .github/project.yml version bump, which IS the publishing act once the central cuioss-organization version-changed guard lets it through, or a deliberate workflow_dispatch from main, which fires unconditionally. Covers the pre-cut safety assertions that must precede the merge, the post-cut "exactly one of each" verification, and the release-notes house format.
user-invocable: true
allowed-tools: Bash, Read, Edit, Write, AskUserQuestion
---

# Release Skill — plan-marshall-mcp

Adapted from API-Sheriff's release runbook (`cuioss/API-Sheriff`, `.claude/skills/release/SKILL.md`).
It drops what this repository does not have: no container-image lane (GHCR, SBOM, Trivy, Cosign),
no non-reactor example POM and no local Quarkus-alignment script. The CI `quarkus-alignment` job of
the Maven build covers that check, and Step 4 gates on it.

The GitHub repository is **`cuioss/plan-marshall-mcp`**. Always pass `--repo cuioss/plan-marshall-mcp` to `gh`.
Temporary files go under `.plan/temp/`.

---

## How the release is wired — READ THIS FIRST

`.github/workflows/release.yml` has exactly two triggers, so there are exactly two release paths:

```yaml
on:
  workflow_dispatch:
  pull_request:
    types: [closed]
    branches: [main]
    paths: ['.github/project.yml']
```

| Path | When it publishes | Role |
|---|---|---|
| **A: merge of a version bump.** A PR into `main` touching `.github/project.yml`, closed with `merged == true`. | Only when the **central guard** (`release-guard` in the pinned `reusable-maven-release.yml`) finds that `release.current-version` changed between the merge commit and its first parent, **and** that no tag for it exists. | The ordinary cut. |
| **B: `workflow_dispatch` from `main`.** | Always. The job's `if:` confines it to `refs/heads/main`; nothing else refuses, not even a re-release. | Fallback: recover a failed run, or cut a version whose declaration already landed. |

### Changing `release.current-version` IS a release

A merged change of `current-version` publishes to **Maven Central** at merge time:
- `de.cuioss:plan-marshall-mcp-parent`
- `de.cuioss:plan-marshall-mcp`

It also creates the tag, the GitHub release and the site deployment. Maven Central releases are
**immutable**: a coordinate can be superseded, never withdrawn. Every safety assertion below therefore
runs **before** the merge.

### How 0.1.0 was cut by accident (2026-09-23)

- The repository was created from `cui-java-module-template`, whose `.github/project.yml` declares
  `current-version: 1.0.0`.
- The bootstrap PR (#2) set it to `0.1.0` in passing, among 50+ other files.
- The guard compared the merge commit against its first parent, saw `1.0.0 -> 0.1.0`, found no tag
  and logged `PROCEED`. `0.1.0` went to Central with the bootstrap code.

**The guard worked as designed; the PR was the release.** Lessons:
- **Never touch `release.current-version` in an ordinary PR.** It changes only in the dedicated
  `chore/release_<version>` PR of Step 1c. A PR that changes it anyway must be split before merge.
- `next-version` is harmless on its own. It drives the SNAPSHOT that `release:prepare` writes after
  a cut, and the guard never looks at it.
- The `paths:` filter is a prefilter. PRs that touch other `.github/project.yml` keys (`maven-build`,
  `sonar`, …) reach the workflow, and the guard correctly refuses them.
- **Never re-implement the guard locally.** A missing or broken central guard is a block to report
  against `cuioss-organization`.

---

## Workflow

> Run every guarded block as one Bash call (its own shell). The `STOP`/`ERROR` branches end in
> `exit 1`: that non-zero status is the guard. Shell variables don't survive between blocks, so each
> block `echo`es what a later block must re-declare.

### Step 1 — Determine the version, choose the path, prepare (do NOT merge)

**1a — Derive versions.** `.github/project.yml` and Maven are the only sources; never git tags or memory.

```bash
eval "$(python3 -c '
import pathlib, re
text = pathlib.Path(".github/project.yml").read_text()
def field(key):
    m = re.search(r"^\s*" + key + r":\s*(\S+)\s*$", text, re.M)
    if not m:
        raise SystemExit(key + " not found in .github/project.yml")
    return m.group(1)
print("PREV_VERSION=" + field("current-version"))
print("DECLARED_NEXT=" + field("next-version"))
')"
POM_VERSION=$(./mvnw -B -q help:evaluate -Dexpression=project.version -DforceStdout -N)
echo "declared: current=$PREV_VERSION next=$DECLARED_NEXT pom=$POM_VERSION"
```

- Resolve the reactor version through Maven; a grep would pick up the parent's `<version>` first.
- Never hand-edit `project.version` in the POMs: `release:prepare` owns it.
- The usual release version is `RELEASE_VERSION="${POM_VERSION%-SNAPSHOT}"`.
- `NEXT_VERSION` (next patch or next minor `-SNAPSHOT`) is a **decision**. Put both numbers to the user
  with `AskUserQuestion` before opening the PR; on Path A there is no later point to reconsider.
- Pre-1.0 (CLAUDE.md): no deprecation cycles, but a released coordinate still can't be taken back.

**1b — Choose the path.**
- `current-version` already declares the intended version → **Path B**. The guard would refuse a merge.
- Otherwise → **Path A**. Prepare the PR in 1c; the merge happens in Step 5.

**1c — Path A only: prepare the version-bump PR, don't merge it.**

```bash
git checkout -b "chore/release_${RELEASE_VERSION}"
# edit .github/project.yml: current-version -> ${RELEASE_VERSION}, next-version -> ${NEXT_VERSION}
git add .github/project.yml
git commit -m "chore(release): declare version ${RELEASE_VERSION}"
git push -u origin "chore/release_${RELEASE_VERSION}"
gh label create skip-bot-review --repo cuioss/plan-marshall-mcp \
  --description "Skip automated bot review" --color ededed 2>/dev/null || true
gh pr create --repo cuioss/plan-marshall-mcp --base main --label "skip-bot-review" \
  --title "chore(release): declare version ${RELEASE_VERSION}" \
  --body "Declare \`current-version\` \`${RELEASE_VERSION}\`, \`next-version\` \`${NEXT_VERSION}\`.

**MERGING THIS PR CUTS THE RELEASE** to Maven Central. Do NOT merge until Steps 2-4 of
\`.claude/skills/release/SKILL.md\` have passed."
```

- The PR changes **only** those two lines.
- Every backtick in the double-quoted body is escaped. An unescaped one starts a command substitution.
- `skip-bot-review` skips the bots only, not CI and not Steps 2–4.

### Step 2 — Clean tree and PR queue

```bash
gh pr list --repo cuioss/plan-marshall-mcp --state open --json number,title,isDraft
git status --porcelain
git fetch origin main && git rev-parse HEAD origin/main
```

- **Open PRs:**
  - Path B: zero.
  - Path A: exactly one, the `chore/release_<version>` PR.
  - Any other open PR → list them and **ask the user** whether to wait.
- **Working tree:** must be clean.
- **HEAD:**
  - Path B: `HEAD == origin/main`.
  - Path A: `git merge-base --is-ancestor origin/main HEAD` must succeed (the branch is up to date).

### Step 3 — Re-assert the pre-cut safety evidence (MANDATORY, at cut time)

**(i) The trigger is still in its guarded form, read at a named SHA.**
Run `git fetch origin main && git rev-parse origin/main` and record the SHA. Then read
`.github/workflows/release.yml` **at that SHA**; for `pull_request` events GitHub uses the base-branch
definition. Confirm all of the following:
1. `on:` is exactly `workflow_dispatch` plus `pull_request` with `types: [closed]`,
   `branches: [main]` and `paths: ['.github/project.yml']`. No `push`, no `schedule`, nothing wider.
2. The `release` job's `if:` still ANDs both operands: the dispatch-to-main operand and the
   `merged == true` operand.
3. `uses:` still pins `reusable-maven-release.yml` to a full SHA with a release comment (`# vX.Y.Z`)
   that includes the central guard (the `release / guard` job of any recent run).

**(ii) `release.yml` is the only caller of `reusable-maven-release.yml`.**
Run `git ls-files .github/workflows/`, then `Read` every file. Use direct enumeration, not a content
search: the architecture inventory does not walk `.github/**`.

**(iii) The queue is quiesced, immediately before the cut.** Re-run the open-PR list from Step 2.
The release **force-pushes to `main` twice** (as the queue-bypass release bot), and a merge racing it
can discard commits.

**(iv) No tag for the release version exists.**

```bash
V=<RELEASE_VERSION>
git fetch --tags --force || { echo "ERROR: tag fetch failed" >&2; exit 1; }
git rev-parse --verify --quiet "refs/tags/$V"; case $? in
  0) echo "STOP: local tag $V exists" >&2; exit 1 ;; 1) echo "OK: no local tag $V" ;;
  *) echo "ERROR: check did not evaluate" >&2; exit 1 ;; esac
git ls-remote --exit-code --tags origin "refs/tags/$V"; case $? in
  0) echo "STOP: remote tag $V exists" >&2; exit 1 ;; 2) echo "OK: no remote tag $V" ;;
  *) echo "ERROR: check did not evaluate" >&2; exit 1 ;; esac
```

- Match the full ref and branch on the exit code. `grep -w 0.1.0` also matches `0.1.0-rc1`.
- On Path B this is the only thing between a re-dispatch and a force-moved tag.

**(v) Check whether the version already exists on Maven Central.**
Run `curl -s -o /dev/null -w '%{http_code}' https://repo1.maven.org/maven2/de/cuioss/plan-marshall-mcp/$V/`.
It must return `404`; `200` means the version is already published, so stop.

### Step 4 — Gate on a green `main`, bound to a SHA

```bash
MAIN_SHA=$(git rev-parse origin/main); echo "$MAIN_SHA"
gh run list --repo cuioss/plan-marshall-mcp --commit "$MAIN_SHA" \
  --json workflowName,event,status,conclusion,databaseId,url
```

- `Maven Build` (including its `quarkus-alignment` and `sonar-build` jobs) and `Integration Tests`
  must be `completed`/`success` for `$MAIN_SHA`.
- An absent, queued or running run is **not a pass**.
- Path A additionally needs the release PR's own checks green (`gh pr checks <n>`) and a green
  `merge_group` run before the entry lands.

### Step 5 — Cut

Record the high-water mark first, in both paths:

```bash
PREV_RUN_ID=$(gh run list --repo cuioss/plan-marshall-mcp --workflow "Release" --limit 1 \
  --json databaseId --jq 'first | .databaseId // 0'); echo "$PREV_RUN_ID"
```

**Path A.** Run `gh pr merge <n> --repo cuioss/plan-marshall-mcp --squash`.
- **Never pass `--delete-branch`.** `main` is merge-queue gated, so the merge only enqueues, and the
  flag closes the PR unmerged.
- Poll the PR `state` or `origin/main` to see what happened.

**Path B.** Re-assert that `main` has not moved, then dispatch in the same block:

```bash
MAIN_SHA=<from Step 4>
git fetch origin main || { echo "ERROR: fetch failed" >&2; exit 1; }
test "$(git rev-parse origin/main)" = "$MAIN_SHA" \
  || { echo "STOP: origin/main moved - redo Steps 3(iii) and 4" >&2; exit 1; }
gh workflow run "Release" --repo cuioss/plan-marshall-mcp --ref main
```

Dispatch with `--ref main`, never with a SHA: the release force-pushes its version commits to a branch.

**Capture exactly one new run** (Path B: add `--event workflow_dispatch --commit "$MAIN_SHA"`;
Path A: `--event pull_request`):

```bash
PREV_RUN_ID=<echoed above>
RUN_ID=$(gh run list --repo cuioss/plan-marshall-mcp --workflow "Release" --event pull_request --limit 20 \
  --json databaseId --jq "[.[] | select(.databaseId > ${PREV_RUN_ID}) | .databaseId] | if length == 1 then .[0] else empty end")
test -n "$RUN_ID" || { echo "STOP: no unique new Release run" >&2; exit 1; }
echo "$RUN_ID"
```

**Read the run; don't infer the outcome from the merge.** The `release / guard` job logs its verdict
(`PROCEED — …` or the reason it skipped):

| The run shows | Decided by | Published? |
|---|---|---|
| no `Release` run | the `paths:`/`branches:` prefilter | nothing |
| `release` skipped | `merged == true` / the dispatch-to-main `if:` | nothing |
| guard skipped the release | central guard: version unchanged or already tagged | nothing |
| guard `PROCEED`, `release` running | the cut | yes, irrevocable once green |

If the guard refused although `current-version` differs between the merge commit and its first
parent and no tag exists, the **guard is broken**. Stop and report it. Don't paper over it with a dispatch.

### Step 6 — Quiescence

Nothing merges to `main` from the cut (on Path A: from **enqueue**) until the run completes. No
mechanism enforces this; it is the operator's obligation.

### Step 7 — Wait

Run `gh run watch "$RUN_ID" --repo cuioss/plan-marshall-mcp` (check that `RUN_ID` is non-empty first).

The release is a single job: Maven release, then site deploy, push of the version commits, tag
push and GitHub release. If it fails **after** `Maven release <v>` succeeded, the jars are on Central:
- Don't dispatch again; it would re-release.
- Establish which step failed.
- Complete the missing tag, push or GitHub release by hand, or cut a new patch version.

### Step 8 — Verify EXACTLY ONE of each

1. **One tag.** Run `git fetch --tags --force`, then check that
   `git ls-remote --refs --tags origin "refs/tags/$V" | wc -l` is `1` (the tag is bare, no prefix).
2. **One GitHub release:** `gh release view "$V" --repo cuioss/plan-marshall-mcp`.
3. **Both coordinates on Central.** A `404` is propagation lag (the release uses `autoPublish=true`;
   around 40 minutes was measured in API-Sheriff). Any other status means the check did not evaluate.
   ```bash
   V=<RELEASE_VERSION>; present=0
   for a in plan-marshall-mcp-parent plan-marshall-mcp; do
     code=$(curl -sS -o /dev/null -w '%{http_code}' "https://repo1.maven.org/maven2/de/cuioss/$a/$V/") || code=err
     case "$code" in 200) present=$((present+1)); echo "OK   $a";; 404) echo "LAG  $a";;
       *) echo "FAIL $a ($code)" >&2; exit 1;; esac
   done
   [ "$present" -eq 2 ] || { echo "NOT YET: $present/2 - re-check later" >&2; exit 1; }
   ```
   The log line *"To finish publishing visit …"* is boilerplate. The operative line is
   *"Deployment will publish automatically"*.
4. **`main` state.** It carries the two `[maven-release-plugin]` commits, and the reactor version is
   `NEXT_VERSION`.

### Step 9 — Version-bearing content

Run `git grep -n "$PREV_VERSION"` and partition every hit:
- **Release-coupled claims** (README install snippets and similar) → update.
- **Historical records** (the incident note above, `release.yml` comments) → never touch.

Read each hit; don't stream-edit the grep output. Report the count, including "checked, nothing to do".

### Step 10 — Reformat the release notes

```bash
gh release view "$V" --repo cuioss/plan-marshall-mcp --json body --jq .body > .plan/temp/release-$V-orig.md
# build .plan/temp/release-$V.md, then:
gh release edit "$V" --repo cuioss/plan-marshall-mcp --notes-file .plan/temp/release-$V.md
```

House format (the same as API-Sheriff):
1. **Top-level groups, in order:**
   - `## Quarkus`: open with *"This release targets **Quarkus X** (previously Y)."*, or state the
     unchanged version.
   - `## Features & Enhancements`: themed `###` sections, e.g. MCP Tools, API & Code Quality,
     Security, Testing & Standards, Documentation, Build & CI.
   - `## Dependency Updates`: `### Java` and `### Infra`.
2. **Collapse by library.** Give one line per library spanning the full range and carrying all PR
   URLs. Recover versions from the PR body when Dependabot truncates a title.
3. **Drop the noise:** OpenRewrite bumps, plan-marshall/`marshal.json` churn and the version-declaration
   PR itself.
4. **Keep the lines as they are.** Each kept line stays in `* <title> by @author in <url>` form, and
   the trailing `**Full Changelog**` line stays.
5. **Cross-check the PRs.** Every original PR is kept, collapsed or deliberately dropped, and no new
   one appears.
6. **Check for duplicates.** Every count from
   `grep -oE '(bump|update) [^ ]+ (from|in)' .plan/temp/release-$V.md | sort | uniq -c` must be 1.

### Step 11 — Report

Report the following:
- the version, the path (A/B), and what the guard logged
- the SHA from Step 3(i)
- the release URL
- the exactly-one results of Step 8, including Central status per coordinate
- the Step 9 counts
- how many PRs were collapsed or dropped in the notes

---

## Critical rules

- **`release.current-version` changes only in a dedicated release PR.** Merging any change to it is a
  Maven Central release.
- **Two paths only.** The merge path is guarded centrally and never re-implemented locally; the
  dispatch path runs from `main` only and refuses nothing.
- **All pre-cut assertions (Steps 2–4) run before the merge or dispatch.** Re-assert 3(i), (iii) and
  (iv) at cut time; never inherit them.
- **Nothing merges during the run.** The release force-pushes to `main`.
- **Never `--delete-branch`** on a queued merge.
- **Verify "exactly one of each", not "it worked".**
- A change that removes or weakens the release trigger merges **on its own**, before anything that
  would fire it: GitHub evaluates `pull_request` workflows from the base branch.
