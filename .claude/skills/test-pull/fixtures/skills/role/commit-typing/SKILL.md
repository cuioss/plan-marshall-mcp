---
name: commit-typing
description: How this project types its commits. Binding for every commit-type decision.
---

# Commit typing

This project types commits by their effect on a consumer of the artefact, not by the kind of file touched.

## Rule D1: a dependency bump is a `fix`

A dependency bump is any change whose only effect is to raise the version of something the build consumes: a library, a build plugin, a parent or BOM, a container base image, a pinned CI action, a lockfile refresh.

A dependency bump is typed `fix`. It is never typed `chore`, `build`, or `ci`, whatever the file is and whatever the reason for the bump. The reason: every bump changes what is shipped or how it is built, and the release tooling of this project cuts a patch release only for `fix`.

## Other types

Changes that are not dependency bumps keep their usual type: `feat`, `fix`, `docs`, `test`, `chore`.

Worked examples: `skill://pm/role/commit-typing/examples.md`.
