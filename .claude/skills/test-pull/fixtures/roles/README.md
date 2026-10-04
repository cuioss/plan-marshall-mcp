# Role configuration sets

A role is one file `<set>/<name>.json`; the roles of a set are exactly its files, and a scenario names
roles only by name. Keys (closed): `description`, `harness` (claude | opencode | agy), `model`, `effort`,
`form` (warm: a supervised worker that takes task after task; fresh: one job per task), `count` (workers
of a warm role, parallel jobs of a fresh one), `skills` (skill URIs in the launch prompt, after core
conduct and the task protocol), `consult` (may ask another role through the server), `idle_wakeups`,
`token_budget`, `recycle_every` (submits per generation; null for none).

`default`: the operator's configuration of 2026-10-04. `changed`: the second configuration of E8.
