# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## Project

plan-marshall-mcp (PM-MCP) is a local MCP server that takes over the process logic of plan-marshall
through a hypermedia-driven workflow: the server drives plans and epics, and harness processes are
supervised workers of configured roles (the harness as worker). The design describes the target state,
most of which is not implemented yet: requirements in `doc/Requirements.adoc` (modules in
`doc/requirements/`), technical specifications in `doc/Specification.adoc` (documents in `doc/specification/`), delivery staging in
`doc/roadmap.adoc`, defect archetypes and fixtures to guard during implementation in
`doc/ImplementationWatch.adoc` (documents in `doc/implementation-watch/`, one per specification that has watch items, plus `cross-cutting.adoc`).

Current state: roadmap Milestone 0 is complete; Milestone 1 is next. Part A established the harness as worker
(concept `doc/concepts/harness-as-worker/`, index `doc/Concepts.adoc`). Part B built each verified technique in
its target module, minimal and real: the always-on daemon `pm-mcpd` (`pm-mcp-server`), reached through the
`pm-mcp serve` STDIO relay (`pm-relay`), the operator CLI `pm-operator`, and the job launcher `pm-exec`, all four
built as native binaries on the host (no container image; PM-TECH-1/3, `doc/specification/runtime-model.adoc`),
beside the library modules of `pm-modules`. The client contract `pm-api` and the three client binaries now live in
the repository `plan-marshall/pm-mcp-clients` and are resolved from the organisation's registry. The measurements of both parts, on macOS and Linux, are the reference
specification `doc/specification/evaluation.adoc`. The code is a first form of each technique: the specifications
stay the target, the workflow engine and the job runtime do not exist yet (the core tools answer as stubs, the
job-token and device registries are empty), and there is no experiment-only code in the tree.

## Modules

The target module structure (PM-IMPL-1 in `doc/requirements/14-implementation.adoc`): `pm-mcp-server`
(Quarkus daemon assembly) beside the aggregator `pm-modules` (library modules, with the nested
`pm-providers`); the client modules are in `pm-mcp-clients`. Milestone 0 created the modules its techniques live in; Milestone 1 adds the
rest. The only listing of the modules, their dependencies and the specification each implements is
`doc/specification/module-structure.adoc`; name modules from there and never repeat the listing
elsewhere. Every module gets minimal real code and tests, never an empty shell.

That listing also defines the target repositories: Milestone 1 splits this repository into `pm-mcp-parent`,
`pm-mcp-clients`, `pm-mcp-core`, `plan-marshall-documentation` and the private assembly that stays here.
`pm-mcp-parent` and `pm-mcp-clients` exist; until a module has moved, it is built here as before. A class that needs no Quarkus, CDI, Vert.x or MCP type does not
belong in `pm-mcp-server`; model-facing content (workflow units, roles, skills, bundles) belongs nowhere else.

## Development Notes

### Build Commands

Never hard-code build tool invocations; use the resolved canonical commands below.

- Compile: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "compile"`
- Quality gate: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Ppre-commit"`
- Full verify: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify"`
- Coverage: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pcoverage"`
- Tests (one module): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl <module-path> -am"`
- Integration tests: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pintegration-tests"`
- Native binaries and their integration tests: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pnative,integration-tests"` with `GRAALVM_HOME` and `JAVA_HOME` set to a GraalVM 25 installation
- The native layout of `pm-e2e` needs `pm-mcp`, `pm-operator` and `pm-exec` as native binaries, which this
  repository no longer builds: it takes them from a checkout of `plan-marshall/pm-mcp-clients` **beside this
  repository** (`../pm-mcp-clients`, or `-Dpm.clients.checkout=<path>`), built there with `./mvnw verify -Pnative`.
  Without them the native layout is skipped and the build output says so (`pm-e2e: native layout skipped: …`); a
  native run that prints that line has not tested the release layout. The JVM layout needs no checkout: it takes
  the client JARs from the registry.
- Without `.plan/execute-script.py` (it is not tracked, for example on a fresh clone): `./mvnw` with the same arguments.
- `.mvn/maven.config` passes `.mvn/settings.xml` (the organisation's package registry, no token) as global settings;
  the token is the server `plan-marshall` of `~/.m2/settings.xml` (`doc/developer/registry-setup.adoc`).
- Use a Bash timeout of 600000ms for build commands.
- Analyze each build's TOON result: `status`, `errors[N]{file,line,message,category}`, `log_file`.

- Always build and test through Maven and JUnit; never run `javac` directly or write ad-hoc verifier classes.
- The quality gate (`-Ppre-commit`) rewrites files (license headers, OpenRewrite recipes, import
  order). Review every resulting diff and commit it; then run Full verify again.
- Java level: `maven.compiler-plugin.release` (25) in the parent POM `pm-mcp-parent`, whose `pre-commit`
  profile overrides the recipe list of the cui parent without `UpgradeToJava21`, which would downgrade the
  release and break unnamed variables (`_`).
- The compiler runs with `failOnWarning`: fix deprecations and warnings, don't suppress them.

## Dependencies and Versions

- `pm-api`, `pm-exec`, `pm-relay` and `pm-operator` come from `pm-mcp-clients` as `SNAPSHOT` versions, named by the
  one property `version.pm-mcp-clients` in the root `pom.xml`; a change to them is a pull request there, and its
  merge deploys the `SNAPSHOT` this repository builds against.
- Parent `de.planmarshall:pm-mcp-parent`, resolved from the organisation's registry, inherits from
  `de.cuioss:cui-quarkus-parent`, which supplies Quarkus (`version.quarkus`), cui-http and cui-java-tools versions.
  Never declare `version.quarkus` locally. The managed third-party versions and the plugin management live in the
  parent; a change there is a release of the parent. The root `pom.xml` manages the modules of this repository only.
- `pm-mcp-parent` imports `quarkus-bom` **first** (smallrye-config convergence; the org
  `quarkus-alignment` CI job fails on a split Quarkus line), then `quarkus-mcp-server-bom`.
- Never add dependencies without asking the user first.
- Pre-1.0: no deprecation cycles, no backward-compatibility shims.

## Code Standards

- Java 25, Lombok (`@UtilityClass`, `@Value`, `@Builder`), prefer records, `var` for obvious types,
  final fields, package-private over public where possible.
- No `module-info.java` in Quarkus modules; set `maven.jar.plugin.automatic.module.name` instead.
- Every package has a `package-info.java` with Javadoc; every public type is documented.
- CDI: constructor injection, `@ApplicationScoped` by default.
- Never catch or throw generic `Exception`/`RuntimeException` in production code.

### Logging

- `private static final CuiLogger LOGGER = new CuiLogger(X.class);` (cui-java-tools). No slf4j,
  log4j, `System.out`/`System.err`.
- `%s` placeholders only; exception first.
- INFO/WARN/ERROR messages as `LogRecord` constants in `PmMcpLogMessages` of `pm-runtime` (prefix `PM_MCP`,
  ranges INFO 001-099, WARN 100-199, ERROR 200-299), each documented in `doc/LogMessages.adoc`.

### Testing

- JUnit 5 only (`@DisplayName`, `@Nested`, AAA, `@ParameterizedTest` for 3+ variants).
  Forbidden: Mockito, PowerMock, Hamcrest.
- `@QuarkusTest` + `McpAssured` (quarkus-mcp-server-test) for MCP tools. The daemon listens only on its
  Unix socket; tests give it a short `PM_MCP_BASE` (the socket path must fit `sun_path`).
- OS-specific behaviour (Landlock, Secret Service, Keychain) is tested with `@EnabledOnOs`; verification
  ITs write their measured figures to `target/verification-results/<item>.json`.
- Test data: cui-test-generator; log assertions: cui-test-juli-logger (`@EnableTestLogger`).
- Minimum 80% instruction and branch coverage per module. Locally, a `-Pcoverage` run enforces it through
  the JaCoCo `check` of the parent's profile (merged Maven and `quarkus-jacoco` data). CI never runs that
  check: it runs `verify -Psonar`, and the bar there is the SonarCloud quality gate on new code.
- Keep a `@QuarkusMain` entry point thin and unit-test its logic in separate classes.
- Behaviour of the packaged binaries belongs in `*IT` tests (run with `-Pintegration-tests`; they start the
  packaged daemon and the client binaries as processes, natively with `-Pnative`), not in unit tests. A test that
  needs a job token, a device secret, or a probe tool gets it from test scope (`TestRegistries`, `ProbeTools` in
  `pm-mcp-server`), never from production code.

## Documentation

AsciiDoc (`.adoc`) for all project documentation. Requirements go into `doc/requirements/`,
technical specifications into `doc/specification/` (traceability rules in `doc/Specification.adoc`; a reference
specification with status `REFERENCE`, such as `evaluation.adoc`, records evidence and defines nothing),
research notes and open proposals into `doc/discussions/` (a decided proposal is applied and then deleted).
The normative documents state the target only: no decision ids, decision dates, or history narration.
Model roles are verified against the corpus `test/model/verification/` (`validate.py`).
Documentation of the implemented system goes into three trees: concepts (`doc/Concepts.adoc`, `doc/concepts/`), developer (`doc/DeveloperGuide.adoc`,
`doc/developer/`) and user (`doc/UserGuide.adoc`, `doc/user/`). Don't create new documents without asking,
except topic documents inside those three trees written by `traced-implementation`.

Every concrete implementation follows the project skill `traced-implementation`: each planned task traces
to its requirements, specification sections and watch items (the _Implementation watch_ line below a
heading, plus `doc/implementation-watch/cross-cutting.adoc`) and assigns each specified statement its
destination (code, test, concept, developer or user documentation); after implementation, coverage is
verified against all three; a requirement the implementation proves wrong is corrected (with evidence) in
the same plan, never worked around in code; the same PR writes the concept, developer and user
documentation for the slice from the specification and watch corpus (describing the implemented system,
verified against the code), deletes the implemented specification sections and watch items, and links each
requirement to its classes, tests and documentation (`Implementation:` / `Verified by:` / `Documentation:`
lines).

## Git Workflow

The repository lives in the `plan-marshall` organisation (`plan-marshall/plan-marshall-mcp`). `main` is protected by rulesets and merges go through the merge queue. Direct pushes to `main` are never allowed. Always use this workflow:

1. Create a feature branch: `git checkout -b <branch-name>`
2. Commit changes: `git add <files> && git commit -m "<message>"`
3. Push the branch: `git push -u origin <branch-name>`
4. Create a PR: `gh pr create --repo plan-marshall/plan-marshall-mcp --head <branch-name> --base main --title "<title>" --body "<body>"`
5. Wait for CI + review bots (waits until checks complete): `gh pr checks --watch`
6. **Handle review comments**: fetch them with `gh api repos/plan-marshall/plan-marshall-mcp/pulls/<pr-number>/comments`. For each one:
   - If it's clearly valid and fixable: fix it, commit, push, then reply explaining the fix and resolve the comment
   - If you disagree or it's out of scope: reply explaining why, then resolve the comment
   - If you're uncertain (not 100% confident): **ask the user** before acting
   - Every comment MUST get a reply (the reason for fixing or not fixing) and MUST be resolved
7. Do **NOT** enable auto-merge unless explicitly instructed. Wait for user approval.
8. Return to main: `git checkout main && git pull`

**Publishing only to the organisation's registry:** plan-marshall-mcp is proprietary. Its artifacts go to the GitHub
Packages registry of the organisation `plan-marshall` and nowhere else, never to Maven Central or another public
registry. The parent POM `de.planmarshall:pm-mcp-parent` (repository `plan-marshall/pm-mcp-parent`) carries that
target for every module (`https://maven.pkg.github.com/plan-marshall/${pm.repository}`, with `pm.repository` set in
the root `pom.xml`), pins it against a target on the command line, keeps the Maven Central publishing of the cui
parent out of the build, and fails the build for any other target. This repository deploys nothing yet: there is
no release workflow, snapshot deploy is off (`.github/project.yml`), the CI build receives no Sonatype or GPG
credentials, and the root `pom.xml` skips `maven-deploy-plugin`, so even `mvn deploy` uploads nothing. Never
re-enable any of these, never weaken the guards of the parent, and never add a deployment target other than the
organisation's registry, without the user's explicit decision.

CI: reusable workflows from `cuioss/cuioss-organization`, pinned by full SHA with a version comment;
configuration in `.github/project.yml`. Releases of `cuioss-organization` and of the cui parent open
their update PRs here through the App `plan-marshall-release-bot` (labelled `skip-bot-review`, merged by
auto-merge). SonarCloud analyses the project in the organisation `plan-marshall`; CodeRabbit is
configured by `.coderabbit.yaml`. The required checks are those of the
ruleset `main-branch-protection`, which is their only authoritative list: read them with
`gh api repos/plan-marshall/plan-marshall-mcp/rulesets` and the ruleset's id.

## IDE Detection

To open a file for the user: if `TERM_PROGRAM=vscode`, use `code <path>`, otherwise `open <path>`.

## Temporary Files

Use `.plan/temp/` for ALL temporary and generated files (covered by `Edit(.plan/**)` permission — avoids permission prompts).

## Tool Usage

- Use proper tools (Edit, Read, Write) instead of shell commands (echo, cat)
- Never use Bash for file operations (find, grep, cat, ls) — use Glob, Read, Grep tools instead
