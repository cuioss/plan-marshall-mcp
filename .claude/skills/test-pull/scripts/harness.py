"""Harness adapters for the pull-mechanism verifications: Claude Code, OpenCode, Antigravity (`agy`).

Everything a harness needs to reach the stub and everything read back from its output lives here.
The timeout knobs in RAISED are taken from host documentation and are unverified; V1 verifies them.
"""
import json
import os
import pathlib
import subprocess

SERVER = "pullstub"
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
    "opencode": "opencode/claude-sonnet-5-5",
    "agy": "gemini-3.1-pro-low",
}
RAISED = {
    "claude": "env MCP_TOOL_TIMEOUT (and CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT over http) = 7200000",
    "opencode": "config mcp.pullstub.timeout and experimental.mcp_timeout = 7200000",
    "agy": "no knob known",
}
RELAY = str(pathlib.Path(__file__).resolve().parent / "relay.py")
AGY_WORKER = "agy"


def relay_argv(url, worker, run_dir):
    """The stdio server command a host starts: the stand-in for `pm-mcp serve`."""
    return ["python3", RELAY, "--url", url, "--worker", worker,
            "--log", str(pathlib.Path(run_dir) / f"relay-{worker}.jsonl")]

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


def _claude_server(url, worker, run_dir, transport):
    if transport == "http":
        return {"type": "http", "url": url}
    command = relay_argv(url, worker, run_dir)
    return {"type": "stdio", "command": command[0], "args": command[1:]}


def _opencode_config(url, raised, worker, run_dir, transport, extra_tools=()):
    server = {"type": "remote", "url": url, "enabled": True}
    if transport != "http":
        server = {"type": "local", "command": relay_argv(url, worker, run_dir), "enabled": True}
    # explicit names: a wildcard would admit a tool the server adds mid-session
    tools = {"*": False, **{f"{SERVER}_{tool}": True for tool in [*TOOLS, *extra_tools]}}
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


def headless(harness, ws, url, prompt, model, worker, transport="stdio", raised=False, resume=None,
             extra_tools=()):
    """Returns (argv, env) for one headless run in the workspace directory `ws` of a run directory."""
    ws = pathlib.Path(ws)
    run_dir = ws.parent
    if harness == "claude":
        config = ws / f"mcp-headless-{worker}.json"
        config.write_text(json.dumps({"mcpServers": {SERVER: _claude_server(url, worker, run_dir, transport)}}))
        allowed = ",".join(f"mcp__{SERVER}__{tool}" for tool in [*TOOLS, *extra_tools])
        argv = ["claude", "-p", prompt, "--mcp-config", str(config), "--strict-mcp-config",
                "--allowedTools", allowed, "--output-format", "stream-json", "--verbose",
                # partial messages carry the final output tokens of every turn (message_delta)
                "--include-partial-messages", "--model", model]
        if resume:
            argv += ["--resume", resume]
        extra = {"MCP_TOOL_TIMEOUT": RAISED_MS, "CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT": RAISED_MS} if raised else {}
        return argv, clean_env(extra)
    if harness == "opencode":
        argv = ["opencode", "run", "--format", "json", "--agent", "pull-worker", "-m", model, "--dir", str(ws)]
        if resume:
            argv += ["-s", resume]
        argv.append(prompt)
        return argv, clean_env({"OPENCODE_CONFIG_CONTENT": json.dumps(
            _opencode_config(url, raised, worker, run_dir, transport, extra_tools))})
    if harness == "agy":
        # --sandbox: the permission bypass is needed for MCP calls in print mode, but a model that loses its
        # MCP server must not roam the machine with shell commands
        argv = ["agy", "-p", prompt, "--output-format", "stream-json", "--dangerously-skip-permissions",
                "--sandbox", "--model", model]
        if resume:
            argv += ["--conversation", resume]
        return argv, clean_env()
    raise ValueError(f"unknown harness {harness}")


def interactive(harness, ws, url, raised, pointer, worker, transport="stdio"):
    """Writes the workspace configuration for a TUI session and returns the launch command line."""
    ws = pathlib.Path(ws)
    run_dir = ws.parent
    if harness == "claude":
        (ws / ".mcp.json").write_text(json.dumps(
            {"mcpServers": {SERVER: _claude_server(url, worker, run_dir, transport)}}, indent=2))
        (ws / "pointer.txt").write_text(pointer)
        hook = {"hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": f"cat '{ws / 'pointer.txt'}'"}]}]}}
        (ws / ".claude").mkdir(exist_ok=True)
        (ws / ".claude" / "settings.json").write_text(json.dumps(hook, indent=2))
        prefix = f"MCP_TOOL_TIMEOUT={RAISED_MS} CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT={RAISED_MS} " if raised else ""
        allowed = ",".join(f"mcp__{SERVER}__{tool}" for tool in TOOLS)
        # without the allowlist every call of the loop would stop at a permission prompt
        return f"cd '{ws}' && {prefix}claude --allowedTools '{allowed}'"
    if harness == "opencode":
        config = _opencode_config(url, raised, worker, run_dir, transport)
        (ws / "opencode.json").write_text(json.dumps(config, indent=2))
        return f"cd '{ws}' && opencode --agent pull-worker"
    if harness == "agy":
        return f"cd '{ws}' && agy"
    raise ValueError(f"unknown harness {harness}")


def agy_register(url, run_dir, transport="stdio"):
    """One global server entry: every agy process of a run shares the worker id `agy`."""
    subprocess.run(["agy", "mcp", "remove", SERVER], capture_output=True, text=True, timeout=60, env=clean_env())
    target = [url] if transport == "http" else ["--", *relay_argv(url, AGY_WORKER, run_dir)]
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
