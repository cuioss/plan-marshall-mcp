# Worker context

What a worker of the evaluation was given besides a task (2026-10-03/04): its launch prompt consisted of
`shim.md`, then `core.md` and `task-protocol.md`, then the skill of its role (`../roles/<role>.md`), then the
instruction to call `pull_wait` (a fresh job: to handle exactly one task). A role's answers in the corpus are
only comparable when given this context.
