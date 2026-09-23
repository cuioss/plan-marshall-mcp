# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## Project

plan-marshall-mcp (PM-MCP) is a local MCP server that takes over the process logic of plan-marshall
through a hypermedia-driven workflow. The design lives in `doc/concept/` (start at
`doc/concept/README.adoc`); it describes the target state, most of which is not implemented yet.

Current state: a Quarkus application with one `hello` MCP tool, packaged as a JVM container image
and verified by container-based integration tests.

## Modules

| Module | Content |
|---|---|
| `plan-marshall-mcp` | Quarkus app (`de.cuioss.pm.mcp`): MCP server (Streamable HTTP at `/mcp`, port 8080), health on management port 9000 (`/q/health`), `src/main/docker/Dockerfile.jvm` |
| `integration-tests` | Builds the image via `docker compose`, starts it (`scripts/`), runs `*IT` tests with RestAssured against ports 18080/19000. Never published |

New modules from the concept (core daemon, front end, skills extension, container runtime adapter,
host build server) are added with their first code, not as empty shells.

## Build Commands

```bash
./mvnw clean install                                              # build + unit tests
./mvnw test -pl plan-marshall-mcp -Dtest=ClassName[#method]       # single test
./mvnw clean verify -Pintegration-tests -pl integration-tests -am # container ITs (needs Docker)
./mvnw clean verify -Pcoverage                                    # coverage (jacoco)
./mvnw -Ppre-commit clean verify -DskipTests                      # pre-commit auto-fix
```

- Always build and test through Maven and JUnit; never run `javac` directly or write ad-hoc verifier classes.
- `-Ppre-commit` rewrites files (license headers, OpenRewrite recipes including Java 21 migration,
  import order). Review every resulting diff and commit it; then run `./mvnw clean install` again.
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

- Java 21, Lombok (`@UtilityClass`, `@Value`, `@Builder`), prefer records, `var` for obvious types,
  final fields, package-private over public where possible.
- No `module-info.java` in Quarkus modules; set `maven.jar.plugin.automatic.module.name` instead.
- Every package has a `package-info.java` with Javadoc; every public type is documented.
- CDI: constructor injection, `@ApplicationScoped` by default.
- Never catch or throw generic `Exception`/`RuntimeException` in production code.

### Logging

- `private static final CuiLogger LOGGER = new CuiLogger(X.class);` (cui-java-tools). No slf4j,
  log4j, `System.out`/`System.err` (the pre-boot `HealthProbe` is the documented exception).
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
- Unit-test logic in separate classes rather than in the `@QuarkusMain` entry point, which is
  excluded from JaCoCo and Sonar coverage.
- Container-level behaviour belongs in `integration-tests` (`*IT`), not in unit tests.

### Container image

- The image `HEALTHCHECK` runs the application itself with `--health-probe` (`HealthProbe`: TCP
  connect to management port 9000). Keep it shell-free: the target image is a native executable on
  distroless (`doc/concept/10-technology.adoc`).
- Base images are digest-pinned; Dependabot updates them.
- Don't add a `healthcheck:` to the compose service: it would override the image's own check.

## Documentation

AsciiDoc (`.adoc`) for all project documentation. Concept decisions go into `doc/concept/`,
analyses and variants into `doc/concept/discussions/`. Don't create new documents without asking.

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

CI: reusable workflows from `cuioss/cuioss-organization`, pinned by full SHA with a version comment;
configuration in `.github/project.yml`. Required checks: `build / conclusion`,
`integration-tests / conclusion`.

## IDE Detection

To open a file for the user: if `TERM_PROGRAM=vscode`, use `code <path>`, otherwise `open <path>`.
