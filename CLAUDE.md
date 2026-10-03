# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## Project

plan-marshall-mcp (PM-MCP) is a local MCP server that takes over the process logic of plan-marshall
through a hypermedia-driven workflow. The design describes the target state, most of which is not
implemented yet: requirements in `doc/Requirements.adoc` (modules in `doc/requirements/`), technical
specifications in `doc/Specification.adoc` (documents in `doc/specification/`), delivery staging in
`doc/roadmap.adoc`, defect archetypes and fixtures to guard during implementation in
`doc/ImplementationWatch.adoc` (documents in `doc/implementation-watch/`, one per specification).

Current state: a Quarkus application with one `hello` MCP tool, verified by unit tests and by
`@QuarkusIntegrationTest` integration tests against the packaged application. No container image
is built (the target is native binaries on the host: the always-on daemon `pm-mcpd`, reached through
the `pm-mcp serve` STDIO relay of the `pm-mcp` CLI, PM-TECH-1/3 and `doc/specification/runtime-model.adoc`).

Roadmap Milestone 0, Part A (verifications V1 to V10 of `doc/discussions/control-direction.adoc`) runs on
throwaway tooling: the stub package `de.cuioss.pm.mcp.spike` (active only with `pm.spike.scenario`) and the
project skills `test-pull` and `test-v1` to `test-v10`; results go to
`doc/discussions/control-direction-measurements.adoc`; the result and the resulting architecture are the concept
`doc/concepts/harness-as-worker/`. The tooling is removed with Milestone 1.

## Modules

| Module | Content |
|---|---|
| `plan-marshall-mcp` | Quarkus app (`de.cuioss.pm.mcp`): MCP server (Streamable HTTP at `/mcp`, port 8080), health on management port 9000 (`/q/health`); `*IT` tests (`@QuarkusIntegrationTest`) run against the packaged application |

Roadmap Milestone 1 creates the target module structure in one step (PM-IMPL-1 in
`doc/requirements/14-implementation.adoc`): the root module becomes `pm-mcp-server` (Quarkus daemon
assembly), beside the aggregators `pm-modules` (library modules, with the nested `pm-providers`)
and `pm-clients`. The only listing of the modules, their dependencies and the specification each
implements is `doc/specification/module-structure.adoc`; name modules from there and never repeat the
listing elsewhere. Every module gets minimal real code and tests, never an empty shell. The table above
and the build commands below change with that milestone.

## Development Notes

### Build Commands

Never hard-code build tool invocations; use the resolved canonical commands below.

- Compile: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "compile"`
- Quality gate: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Ppre-commit"`
- Full verify: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify"`
- Coverage: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pcoverage"`
- Tests (plan-marshall-mcp): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl plan-marshall-mcp -am"` — only on plan-marshall-mcp
- Integration tests (plan-marshall-mcp): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pintegration-tests -pl plan-marshall-mcp -am"` — only on plan-marshall-mcp
- Use a Bash timeout of 600000ms for build commands.
- Analyze each build's TOON result: `status`, `errors[N]{file,line,message,category}`, `log_file`.

- Always build and test through Maven and JUnit; never run `javac` directly or write ad-hoc verifier classes.
- The quality gate (`-Ppre-commit`) rewrites files (license headers, OpenRewrite recipes, import
  order). Review every resulting diff and commit it; then run Full verify again.
- Java level: `maven.compiler-plugin.release` (25) in the root `pom.xml`. The root `pre-commit`
  profile overrides the parent's recipe list without `UpgradeToJava21`, which would downgrade the
  release and break unnamed variables (`_`).
- The compiler runs with `failOnWarning`: fix deprecations and warnings, don't suppress them.

## Dependencies and Versions

- Parent `de.cuioss:cui-quarkus-parent` supplies Quarkus (`version.quarkus`), cui-http and
  cui-java-tools versions. Never declare `version.quarkus` locally.
- Root `pom.xml` imports `quarkus-bom` **first** (smallrye-config convergence; the org
  `quarkus-alignment` CI job fails on a split Quarkus line), then `token-sheriff-bom` and
  `quarkus-mcp-server-bom`.
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
- INFO/WARN/ERROR messages as `LogRecord` constants in `PmMcpLogMessages` (prefix `PM_MCP`,
  ranges INFO 001-099, WARN 100-199, ERROR 200-299), each documented in `doc/LogMessages.adoc`.

### Testing

- JUnit 5 only (`@DisplayName`, `@Nested`, AAA, `@ParameterizedTest` for 3+ variants).
  Forbidden: Mockito, PowerMock, Hamcrest.
- `@QuarkusTest` + `McpAssured` (quarkus-mcp-server-test) for MCP tools; management endpoints via
  `@TestHTTPResource(value = "/health/ready", management = true)`.
- Test data: cui-test-generator; log assertions: cui-test-juli-logger (`@EnableTestLogger`).
- Minimum 80% instruction and branch coverage, enforced by the JaCoCo `check` of the parent's
  `-Pcoverage` profile (merged Maven and `quarkus-jacoco` data) and by the SonarCloud quality gate.
- Keep a `@QuarkusMain` entry point thin and unit-test its logic in separate classes.
- Behaviour of the packaged application belongs in `*IT` tests (`@QuarkusIntegrationTest`, run with
  `-Pintegration-tests`), not in unit tests.

## Documentation

AsciiDoc (`.adoc`) for all project documentation. Requirements go into `doc/requirements/`,
technical specifications into `doc/specification/` (traceability rules in `doc/Specification.adoc`),
analyses and variants into `doc/discussions/`. Documentation of the implemented system goes into three
trees: concepts (`doc/Concepts.adoc`, `doc/concepts/`), developer (`doc/DeveloperGuide.adoc`,
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

All cuioss repositories have branch protection on `main`. Direct pushes to `main` are never allowed. Always use this workflow:

1. Create a feature branch: `git checkout -b <branch-name>`
2. Commit changes: `git add <files> && git commit -m "<message>"`
3. Push the branch: `git push -u origin <branch-name>`
4. Create a PR: `gh pr create --repo cuioss/plan-marshall-mcp --head <branch-name> --base main --title "<title>" --body "<body>"`
5. Wait for CI + review bots (waits until checks complete): `gh pr checks --watch`
6. **Handle review comments**: fetch them with `gh api repos/cuioss/plan-marshall-mcp/pulls/<pr-number>/comments`. For each one:
   - If it's clearly valid and fixable: fix it, commit, push, then reply explaining the fix and resolve the comment
   - If you disagree or it's out of scope: reply explaining why, then resolve the comment
   - If you're uncertain (not 100% confident): **ask the user** before acting
   - Every comment MUST get a reply (the reason for fixing or not fixing) and MUST be resolved
7. Do **NOT** enable auto-merge unless explicitly instructed. Wait for user approval.
8. Return to main: `git checkout main && git pull`

**Releases:** merging a change of `release.current-version` in `.github/project.yml` publishes to Maven
Central (central version-changed guard, see `.github/workflows/release.yml`). Never change it in an
ordinary PR; releases go through the runbook `.claude/skills/release/SKILL.md`.

CI: reusable workflows from `cuioss/cuioss-organization`, pinned by full SHA with a version comment;
configuration in `.github/project.yml`. Required checks: `build / conclusion`,
`integration-tests / conclusion`.

## IDE Detection

To open a file for the user: if `TERM_PROGRAM=vscode`, use `code <path>`, otherwise `open <path>`.

## Temporary Files

Use `.plan/temp/` for ALL temporary and generated files (covered by `Edit(.plan/**)` permission — avoids permission prompts).

## Tool Usage

- Use proper tools (Edit, Read, Write) instead of shell commands (echo, cat)
- Never use Bash for file operations (find, grep, cat, ls) — use Glob, Read, Grep tools instead
