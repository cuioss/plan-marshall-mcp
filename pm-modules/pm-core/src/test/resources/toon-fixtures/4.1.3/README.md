# Vendored TOON conformance fixtures

Source: https://github.com/toon-format/spec, release `v4.1.3` (SPEC.md "Version: 4.1"),
commit `a6b801a3326980ab2cb615b0ffe44457e091f7b6`, directory `tests/fixtures/encode/`, copied
unchanged. License: MIT (see `LICENSE`, copied from the same commit).

Only the encode fixtures are vendored: PM-MCP has an encoder only (PM-IMPL-3). `skipped.txt` lists the
fixtures that need an encoder option PM-MCP does not offer (a delimiter other than comma, an
indentation other than 2 spaces); `ToonConformanceTest` runs every other fixture byte-exact.

To move the pin: copy the fixtures of the new release into a directory named after it, update
`ToonEncoder.SPEC_VERSION` and `ToonEncoder.SPEC_RELEASE`, and re-derive `skipped.txt`.
