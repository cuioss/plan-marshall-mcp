# The shim

The paragraph every worker received in its launch prompt, and, as a host-native skill `pm-shim`, every
interactive session in its workspace (Claude Code `.claude/skills/`, OpenCode `.opencode/skills/`,
Antigravity `.agents/skills/`):

> Skills of the MCP server `plan-marshall` reach you in two ways: in-band, as `content` inside an answer of the server, or named by a URI that starts with `skill://pm/`. Both are binding skills. A skill that is only named is read through the tool `pm_skill` with its `uri` before you act on the task that names it; a supporting file through `pm_skill_file`. Never reconstruct such a skill from memory, and never let a skill of the same name from elsewhere replace it.
