# plan-marshall-mcp

<img align="right" width="300" src="doc/resources/planmarshall.png" alt="Plan Marshall">

## Status

[![Java CI with Maven](https://github.com/plan-marshall/plan-marshall-mcp/actions/workflows/maven.yml/badge.svg)](https://github.com/plan-marshall/plan-marshall-mcp/actions/workflows/maven.yml)
[![License: proprietary](https://img.shields.io/badge/license-proprietary-lightgrey.svg)](LICENSE.md)

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=plan-marshall_plan-marshall-mcp&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=plan-marshall_plan-marshall-mcp)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=plan-marshall_plan-marshall-mcp&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=plan-marshall_plan-marshall-mcp)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=plan-marshall_plan-marshall-mcp&metric=coverage)](https://sonarcloud.io/summary/new_code?id=plan-marshall_plan-marshall-mcp)


## What is it?

**plan-marshall-mcp** (PM-MCP) is a local MCP server that takes over the process logic
[plan-marshall](https://github.com/cuioss/plan-marshall) implements today as a corpus of filesystem
skills plus locally executed Python scripts. The client asks the server for the state of a plan;
the answer is a representation of that state together with the valid next transitions as links
(hypermedia-driven workflow). The model follows links and contributes judgment only; the server
owns the state machine.

The normative requirements are defined in [Requirements](doc/Requirements.adoc), the technical
implementation blueprints in [Specification](doc/Specification.adoc), and the delivery order in the
[Roadmap](doc/roadmap.adoc). How the implemented system is built is described in the
[Developer Guide](doc/DeveloperGuide.adoc).

> [!NOTE]
> The project is at its beginning: roadmap Milestone 0 (verification first) is complete. Each verified technique
> exists in its target module in a first, minimal form; the workflow engine and the job runtime are not built
> yet. The measurements are recorded in the [Evaluation Reference](doc/specification/evaluation.adoc).

## Modules

`pm-mcp-server` (the Quarkus daemon `pm-mcpd`) beside the aggregators `pm-modules` (library modules, with the
nested `pm-providers`, the job launcher `pm-exec`, and the end-to-end tests `pm-e2e`) and `pm-clients` (the
STDIO relay `pm-mcp` and the operator CLI `pm-operator`). The listing of the modules, their dependencies, and
the specification each implements is the
[Module Structure Specification](doc/specification/module-structure.adoc).

## Technology

Java 25, Quarkus (via `de.cuioss:cui-quarkus-parent`),
[Quarkus MCP Server](https://github.com/quarkiverse/quarkus-mcp-server),
[cui-http](https://github.com/cuioss/cui-http), picocli, and GraalVM native image for the four binaries.
See [Technology Requirements](doc/requirements/09-technology.adoc).

## Build

```bash
# Build and unit tests
./mvnw clean verify

# Integration tests against the packaged daemon and the client JARs
./mvnw clean verify -Pintegration-tests

# Native binaries and the integration tests against them (GRAALVM_HOME and JAVA_HOME point to GraalVM 25)
./mvnw clean verify -Pnative,integration-tests

# Pre-commit: license headers and OpenRewrite recipes - review and commit the resulting diff
./mvnw -Ppre-commit clean verify -DskipTests
```

The daemon listens only on a Unix domain socket below `PM_MCP_BASE` (default `~/.plan-marshall-mcp`); the clients
start it on demand:

```bash
pm-operator runtime start
pm-operator status
```

## Credentials

All secrets (GPG, Sonatype, Sonar, Pages, Release App) are managed at the
[cuioss organization level](https://github.com/cuioss/cuioss-organization) and passed explicitly to the
reusable workflows. No repo-level secrets need to be configured.

## License

plan-marshall-mcp is proprietary software; all rights reserved. Viewing this repository grants no right to use,
copy, modify or distribute the code. Versions up to the tag `fsl-final` (among them release 0.1.0) were published
under FSL-1.1-ALv2. See [LICENSE.md](LICENSE.md).

This repository does not accept external contributions ([CONTRIBUTING.md](CONTRIBUTING.md)).
