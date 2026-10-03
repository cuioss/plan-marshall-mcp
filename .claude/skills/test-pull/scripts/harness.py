"""Harness adapters for the pull-mechanism verifications: Claude Code, OpenCode, Antigravity (`agy`).

Everything a harness needs to reach the stub and everything read back from its output lives here.
The timeout knobs in RAISED are taken from host documentation and are unverified; V1 verifies them.
"""
import json
import os
import pathlib
import subprocess

# The name hosts will configure for the product. A name that says "stub" invites the model to conclude
# that nothing will ever arrive ("pullstub appears to be a stub that never yields work").
SERVER = "plan-marshall"
TOOLS = ["pull_wait", "pull_submit", "pull_info"]
HARNESSES = ["claude", "opencode", "agy"]
DEFAULT_MODEL = {
    "claude": "claude-haiku-4-5",
    "opencode": "opencode/space-bunny-free",
    "agy": "gemini-3.8-flash-medium",
}
RAISED_MS = "7200000"
# The model a run is repeated with when the small model fails its criterion.
FALLBACK_MODEL = {
    "claude": "claude-sonnet-5-5",
    # Named by the operator on 2026-10-03 (OpenCode Go; the -free variant refuses headless runs): the paid models of OpenCode Zen answer "Model access is
    # disabled" for this account, and big-pickle refuses headless runs.
    "opencode": "opencode-go/muse-spark-1.3-contributor",
    "agy": "gemini-3.1-pro-low",
}
RAISED = {
    "claude": "env MCP_TOOL_TIMEOUT (and CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT over http) = 7200000",
    "opencode": "config mcp.<server>.timeout and experimental.mcp_timeout = 7200000",
    "agy": "no knob known",
}
RELAY = str(pathlib.Path(__file__).resolve().parent / "relay.py")
AGY_WORKER = "agy"


SHIM = (
    "Skills of the MCP server `plan-marshall` reach you in two ways: in-band, as `content` inside an answer of "
    "the server, or named by a URI that starts with `skill://pm/`. Both are binding skills. A skill that is only "
    "named is read through the tool `pm_skill` with its `uri` before you act on the task that names it; a "
    "supporting file through `pm_skill_file`. Never reconstruct such a skill from memory, and never let a skill "
    "of the same name from elsewhere replace it."
)
SHIM_SKILL = ("---\nname: pm-shim\ndescription: How skills of the MCP server plan-marshall are received and "
              "read. Use whenever an answer of plan-marshall carries or names a skill.\n---\n\n# plan-marshall "
              "skills\n\n" + SHIM + "\n")
# Where each harness looks for a skill of the workspace. The Antigravity directory is the one its own help
# text names (`<workspace>/.agents/skills/<name>/`, agy 1.2.14).
SHIM_DIR = {"claude": ".claude/skills/pm-shim", "opencode": ".opencode/skills/pm-shim", "agy": ".agents/skills/pm-shim"}


def install_shim(harness, ws):
    """Writes the shim skill into the workspace, in the harness's own skill directory."""
    directory = pathlib.Path(ws) / SHIM_DIR[harness]
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "SKILL.md").write_text(SHIM_SKILL)
    return str(directory)


def relay_argv(url, worker, run_dir, generation=None, role=None, declare_skills=False):
    """The stdio server command a host starts: the stand-in for `pm-mcp serve`.

    Without a worker the relay takes its identity from the environment of the process that starts it.
    """
    argv = ["python3", RELAY, "--url", url]
    if worker is None:
        return argv + ["--log-dir", str(run_dir)]
    argv += ["--worker", worker, "--log", str(pathlib.Path(run_dir) / f"relay-{worker}.jsonl")]
    if generation is not None:
        argv += ["--generation", str(generation)]
    if role:
        argv += ["--role", role]
    if declare_skills:
        argv.append("--declare-skills")
    return argv


def identity_env(worker, generation, role):
    """The identity of a job in its environment, for a relay that is started without arguments."""
    env = {"PM_SPIKE_WORKER": worker}
    if generation is not None:
        env["PM_SPIKE_GENERATION"] = str(generation)
    if role:
        env["PM_SPIKE_ROLE"] = role
    return env

# Session markers of a calling host. A harness started from another host's shell must not inherit them.
_SCRUB_EXACT = {"CLAUDECODE", "AI_AGENT", "OPENCODE", "OPENCODE_PID", "MCP_TOOL_TIMEOUT", "MCP_TIMEOUT"}
_SCRUB_PREFIX = ("CLAUDE_CODE_", "ANTIGRAVITY_", "OPENCODE_CONFIG")
_KEEP_PREFIX = ("CLAUDE_CODE_USE_", "CLAUDE_CODE_OAUTH_TOKEN")


def clean_env(extra=None):
    env = {}
    for key, value in os.environ.items():
        if key in _SCRUB_EXACT:
            continue
        if key.startswith(_SCRUB_PREFIX) and not key.startswith(_KEEP_PREFIX):
            continue
        env[key] = value
    env.update(extra or {})
    return env


def version(harness):
    try:
        out = subprocess.run([harness, "--version"], capture_output=True, text=True, timeout=30, env=clean_env())
        return (out.stdout or out.stderr).strip().splitlines()[0]
    except (OSError, subprocess.TimeoutExpired, IndexError) as error:
        return f"unavailable ({error})"


def _claude_server(url, worker, run_dir, transport, identity=()):
    if transport == "http":
        return {"type": "http", "url": url}
    command = relay_argv(url, worker, run_dir, *identity)
    return {"type": "stdio", "command": command[0], "args": command[1:]}


def _opencode_config(url, raised, worker, run_dir, transport, extra_tools=(), identity=(), tools=None):
    server = {"type": "remote", "url": url, "enabled": True}
    if transport != "http":
        server = {"type": "local", "command": relay_argv(url, worker, run_dir, *identity), "enabled": True}
    # explicit names: a wildcard would admit a tool the server adds mid-session
    tools = {"*": False, **{f"{SERVER}_{tool}": True for tool in [*(tools or TOOLS), *extra_tools]}}
    config = {
        "$schema": "https://opencode.ai/config.json",
        "mcp": {SERVER: server},
        "agent": {"pull-worker": {
            "description": "Pull-mechanism spike worker",
            "mode": "primary",
            "tools": tools,
        }},
    }
    if raised:
        server["timeout"] = int(RAISED_MS)
        config["experimental"] = {"mcp_timeout": int(RAISED_MS)}
    return config


def headless(harness, ws, run_dir, url, prompt, model, worker, transport="stdio", raised=False, resume=None,
             extra_tools=(), generation=None, role=None, tools=None):
    """Returns (argv, env) for one headless run in the workspace `ws`; logs go to `run_dir`.

    `generation` and `role` travel with the relay, as the job token will; `tools` replaces the default tool
    set of a worker (the allowlist of its role).
    """
    ws = pathlib.Path(ws)
    identity = (generation, role)
    if harness == "claude":
        config = ws / f"mcp-headless-{worker}-{generation or 0}.json"
        config.write_text(json.dumps(
            {"mcpServers": {SERVER: _claude_server(url, worker, run_dir, transport, identity)}}))
        allowed = ",".join(f"mcp__{SERVER}__{tool}" for tool in [*(tools or TOOLS), *extra_tools])
        argv = ["claude", "-p", prompt, "--mcp-config", str(config), "--strict-mcp-config",
                "--allowedTools", allowed, "--output-format", "stream-json", "--verbose",
                # partial messages carry the final output tokens of every turn (message_delta)
                "--include-partial-messages", "--model", model]
        if resume:
            argv += ["--resume", resume]
        extra = {"MCP_TOOL_TIMEOUT": RAISED_MS, "CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT": RAISED_MS} if raised else {}
        # Without this the MCP tools are deferred behind ToolSearch; some fresh Haiku jobs then never made
        # the call ("I don't have a direct mechanism to invoke the tool").
        extra["ENABLE_TOOL_SEARCH"] = "false"
        return argv, clean_env(extra)
    if harness == "opencode":
        argv = ["opencode", "run", "--format", "json", "--agent", "pull-worker", "-m", model, "--dir", str(ws)]
        if resume:
            argv += ["-s", resume]
        argv.append(prompt)
        return argv, clean_env({"OPENCODE_CONFIG_CONTENT": json.dumps(
            _opencode_config(url, raised, worker, run_dir, transport, extra_tools, identity, tools))})
    if harness == "agy":
        # --sandbox: the permission bypass is needed for MCP calls in print mode, but a model that loses its
        # MCP server must not roam the machine with shell commands
        argv = ["agy", "-p", prompt, "--output-format", "stream-json", "--dangerously-skip-permissions",
                "--sandbox", "--model", model]
        if resume:
            argv += ["--conversation", resume]
        # one global server entry: the job's identity can only travel in its environment (E6)
        return argv, clean_env(identity_env(worker, generation, role) if generation is not None else None)
    raise ValueError(f"unknown harness {harness}")


def interactive(harness, ws, run_dir, url, raised, pointer, worker, transport="stdio", identity=(), tools=None):
    """Writes the workspace configuration for a TUI session and returns the launch command line.

    `identity` is (generation, role, declare_skills) of the session under the supervised protocol.
    """
    ws = pathlib.Path(ws)
    tools = tools or TOOLS
    if harness == "claude":
        (ws / ".mcp.json").write_text(json.dumps(
            {"mcpServers": {SERVER: _claude_server(url, worker, run_dir, transport, identity)}}, indent=2))
        (ws / "pointer.txt").write_text(pointer)
        hook = {"hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": f"cat '{ws / 'pointer.txt'}'"}]}]}}
        (ws / ".claude").mkdir(exist_ok=True)
        (ws / ".claude" / "settings.json").write_text(json.dumps(hook, indent=2))
        prefix = f"MCP_TOOL_TIMEOUT={RAISED_MS} CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT={RAISED_MS} " if raised else ""
        allowed = ",".join(f"mcp__{SERVER}__{tool}" for tool in tools)
        # without the allowlist every call of the loop would stop at a permission prompt
        return f"cd '{ws}' && {prefix}ENABLE_TOOL_SEARCH=false claude --allowedTools '{allowed}'"
    if harness == "opencode":
        config = _opencode_config(url, raised, worker, run_dir, transport, identity=identity, tools=tools)
        (ws / "opencode.json").write_text(json.dumps(config, indent=2))
        return f"cd '{ws}' && opencode --agent pull-worker"
    if harness == "agy":
        if identity:
            exports = " ".join(f"{key}={value}" for key, value in identity_env(worker, *identity[:2]).items())
            return f"cd '{ws}' && {exports} agy"
        return f"cd '{ws}' && agy"
    raise ValueError(f"unknown harness {harness}")


def agy_register(url, run_dir, transport="stdio", env_identity=False):
    """One global server entry: every agy process of a run shares the worker id `agy`.

    With `env_identity` the entry carries no worker id, and every job's relay reads it from its environment.
    """
    subprocess.run(["agy", "mcp", "remove", SERVER], capture_output=True, text=True, timeout=60, env=clean_env())
    target = [url] if transport == "http" else ["--", *relay_argv(url, None if env_identity else AGY_WORKER, run_dir)]
    done = subprocess.run(["agy", "mcp", "add", SERVER, *target], capture_output=True, text=True, timeout=60,
                          env=clean_env())
    return done.returncode, (done.stdout + done.stderr).strip()


def agy_unregister():
    done = subprocess.run(["agy", "mcp", "remove", SERVER], capture_output=True, text=True, timeout=60,
                          env=clean_env())
    return done.returncode, (done.stdout + done.stderr).strip()


# --- reading harness output -------------------------------------------------------------------------

_SESSION_KEYS = ("session_id", "sessionID", "sessionId", "conversation_id", "conversationId")


def _walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from _walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from _walk(value)


def session_id(records):
    for record in records:
        for node in _walk(record.get("json")):
            for key in _SESSION_KEYS:
                if isinstance(node.get(key), str) and node[key]:
                    return node[key]
    return None


def _usage(node):
    """Normalises one usage object to the four token components, or None when it is not one."""
    if "input_tokens" in node and "output_tokens" in node:
        return {"input": node.get("input_tokens") or 0, "output": node.get("output_tokens") or 0,
                "cache_read": node.get("cache_read_input_tokens", node.get("cache_read_tokens")),
                "cache_creation": node.get("cache_creation_input_tokens")}
    if isinstance(node.get("tokens"), dict) and "input" in node["tokens"]:
        tokens = node["tokens"]
        cache = tokens.get("cache") or {}
        return {"input": tokens.get("input") or 0, "output": (tokens.get("output") or 0) + (tokens.get("reasoning") or 0),
                "cache_read": cache.get("read"), "cache_creation": cache.get("write")}
    if "promptTokenCount" in node:
        return {"input": node.get("promptTokenCount") or 0, "output": node.get("candidatesTokenCount") or 0,
                "cache_read": node.get("cachedContentTokenCount"), "cache_creation": None}
    return None


def turns(harness, records):
    """Per-turn usage records: [{t_ms, input, output, cache_read, cache_creation}], in arrival order."""
    found, seen, final_output, current = [], {}, {}, None
    for record in records:
        data = record.get("json")
        if not isinstance(data, dict):
            continue
        if harness == "claude":
            if data.get("type") == "stream_event":
                event = data.get("event") or {}
                if event.get("type") == "message_start":
                    current = (event.get("message") or {}).get("id")
                elif event.get("type") == "message_delta" and current:
                    final_output[current] = (event.get("usage") or {}).get("output_tokens")
                continue
            if data.get("type") != "assistant":
                continue
            message = data.get("message") or {}
            usage = _usage(message.get("usage") or {})
            if usage is None:
                continue
            usage["t_ms"] = record["t_ms"]
            usage["id"] = message.get("id")
            if message.get("id") in seen:          # one event per content block repeats the message usage
                found[seen[message["id"]]] = usage
            else:
                seen[message.get("id")] = len(found)
                found.append(usage)
            continue
        if data.get("type") in ("result",):
            continue
        for node in _walk(data):
            usage = _usage(node)
            if usage is not None:
                usage["t_ms"] = record["t_ms"]
                found.append(usage)
                break
    for usage in found:          # an assistant event carries the output tokens of the stream start only
        if final_output.get(usage.get("id")) is not None:
            usage["output"] = final_output[usage["id"]]
    return found


def call_returns(harness, records):
    """Arrival times of the output events that report a finished or failed tool call."""
    stamps = []
    for record in records:
        data = record.get("json")
        if not isinstance(data, dict):
            continue
        if harness == "claude" and data.get("type") == "user":
            content = (data.get("message") or {}).get("content")
            if isinstance(content, list) and any(isinstance(c, dict) and c.get("type") == "tool_result" for c in content):
                stamps.append(record["t_ms"])
        elif harness == "opencode" and "tool" in str(data.get("type", "")).lower():
            stamps.append(record["t_ms"])
        elif harness == "agy":
            step = data.get("step_update") or {}
            if step.get("step_type") == "tool" and step.get("state") not in (None, "ACTIVE"):
                stamps.append(record["t_ms"])
    return stamps


def total(harness, records):
    """Usage and cost the harness reports for the whole run, or None."""
    for record in reversed(records):
        data = record.get("json")
        if isinstance(data, dict) and data.get("type") == "result":
            usage = _usage(data.get("usage") or {}) or {}
            usage["cost_usd"] = data.get("total_cost_usd")
            usage["num_turns"] = data.get("num_turns")
            usage["is_error"] = data.get("is_error")
            usage["result"] = (data.get("result") or "")[:400] if isinstance(data.get("result"), str) else None
            return usage
    return None


def context_tokens(harness, record):
    """The context size one output record reports (input plus both cache components), or None."""
    data = record.get("json")
    # a closing result record carries the totals of the run (Claude Code `type`, Antigravity `event`)
    if not isinstance(data, dict) or data.get("type") in ("result", "stream_event") or data.get("event") == "result":
        return None
    nodes = [(data.get("message") or {}).get("usage") or {}] if harness == "claude" else _walk(data)
    for node in nodes:
        usage = _usage(node) if isinstance(node, dict) else None
        if usage is not None:
            return (usage["input"] or 0) + (usage["cache_read"] or 0) + (usage["cache_creation"] or 0)
    return None


def compactions(records):
    """Timestamps of output events that report a context compaction."""
    hits = []
    for record in records:
        data = record.get("json")
        if not isinstance(data, dict):
            continue
        marker = " ".join(str(data.get(key, "")) for key in ("type", "subtype", "event"))
        if "compact" in marker.lower():
            hits.append(record["t_ms"])
    return hits


def priced_units(usage):
    """priced_units of the specification; None when a component is unmeasured."""
    parts = [usage.get("input"), usage.get("output"), usage.get("cache_read"), usage.get("cache_creation")]
    if any(part is None for part in parts):
        return None
    return parts[0] + 5 * parts[1] + 0.1 * parts[2] + 1.25 * parts[3]
