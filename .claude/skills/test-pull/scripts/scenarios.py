"""Scenario builders and prompts for the pull-mechanism verifications V1 to V10.

A scenario is the JSON script the stub (`de.cuioss.pm.mcp.spike`) plays: wait steps, tasks, done.
Every builder returns `(scenario, plan)`; `plan` tells the supervisor in pull.py how to drive the
harness (one loop session, fresh processes, kill trials, ...).
"""
import json
import pathlib
import uuid

FIXTURES = pathlib.Path(__file__).resolve().parents[1] / "fixtures"

LOOP_PROMPT = (
    "You are a worker connected to the MCP server `pullstub`. Your worker id is `{worker}`; pass it as "
    "the argument `worker` in every pull_wait and pull_submit call. Call the tool pull_wait now. "
    "The call blocks and returns JSON with a `status`. "
    "If status is `wait_again`, call pull_wait again immediately. "
    "If status is `task`, decide it from the task's own content only, call pull_submit with its task_id, "
    "a decision that is exactly one of the task's options, and a one-sentence rationale, "
    "then call pull_wait again. "
    "If status is `done`, reply with the single word DONE and stop. "
    "Until then never stop, never ask a question, never write explanations between calls, "
    "and never call any other tool."
)

ONE_TASK_PROMPT = (
    "You are a worker connected to the MCP server `pullstub`. Your worker id is `{worker}`; pass it as "
    "the argument `worker` in every call. Call the tool pull_wait once. "
    "It returns a task. Decide it from the task's own content only and call pull_submit with its task_id, "
    "a decision that is exactly one of the task's options, and a one-sentence rationale. "
    "Then reply with the single word DONE and stop. Call no other tool."
)

RESUME_PROMPT = "Reply with only the nonce that the first task of this session contained."

POINTER = (
    "scope_type: plan\nscope_id: pull-spike\nphase: execute\n"
    "next call: pull_wait(worker=\"tui\") on the MCP server pullstub, then follow the `next` field of "
    "every answer\n"
)

TUI_WORKER = "tui"
V1_CAP_SECONDS = 3600
CACHE_GAP_SECONDS = 330


def tasks():
    return json.loads((FIXTURES / "tasks.json").read_text())["tasks"]


def injections():
    return json.loads((FIXTURES / "injections.json").read_text())


def task_step(task, task_id=None, delay=0, writer=False):
    body = {k: task[k] for k in ("kind", "question", "facts", "options")}
    return {"kind": "task", "id": task_id or task["id"], "delay_seconds": delay, "writer": writer, "task": body}


def wait(repeat=1, seconds=None, pad_bytes=0):
    step = {"kind": "wait", "repeat": repeat, "pad_bytes": pad_bytes}
    if seconds is not None:
        step["seconds"] = seconds
    return step


DONE = {"kind": "done"}


def build(v, opts):
    """opts: cell, smoke, cycles, wait, reps, rounds, variant, fresh."""
    return BUILDERS[v](opts)


def v1(opts):
    cap = 90 if opts["smoke"] else V1_CAP_SECONDS
    progress = 5 if opts["cell"].endswith("-prog") else 0
    scenario = {"wait_seconds": cap, "progress_seconds": progress, "steps": [wait(1), DONE]}
    return scenario, {"kind": "loop", "deadline_s": cap + 300, "raised": opts["cell"].startswith("raised")}


def v2(opts):
    cycles = 4 if opts["smoke"] else opts["cycles"] or 240
    seconds = 5 if opts["smoke"] else opts["wait"] or 30
    scenario = {"wait_seconds": seconds, "steps": [wait(cycles), DONE]}
    return scenario, {"kind": "loop", "deadline_s": cycles * seconds * 2 + 600}


def v10(opts):
    if opts["smoke"]:
        steps, deadline = [wait(6, 5), wait(2, 2, 2000), DONE], 900
    else:
        # 200 plain cycles (100 min) for /clear and manual compaction, then 80 padded cycles
        # (about 5k tokens each) that force an automatic compaction.
        steps, deadline = [wait(200, opts["wait"] or 30), wait(80, 15, 20000), DONE], 4 * 3600
    return {"steps": steps}, {"kind": "interactive", "deadline_s": deadline}


def _task_rounds(rounds, pad_bytes):
    steps = []
    for r in range(rounds):
        for task in tasks():
            steps.append(task_step(task, f"r{r}-{task['id']}"))
            if pad_bytes:
                steps.append(wait(1, 1, pad_bytes))
    return steps


def v3(opts):
    if opts["fresh"]:
        reps = 1 if opts["smoke"] else opts["reps"] or 3
        base = tasks()[:2] if opts["smoke"] else tasks()
        steps = [task_step(t, f"r{r}-{t['id']}") for r in range(reps) for t in base]
        return {"steps": steps + [DONE]}, {"kind": "fresh", "count": len(steps), "gap_s": 0}
    if opts["smoke"]:
        steps = [task_step(t, f"r0-{t['id']}") for t in tasks()[:3]]
        return {"wait_seconds": 2, "steps": steps + [DONE]}, {"kind": "loop", "deadline_s": 600}
    # 8 KB of filler after every task grows the context by about 2k tokens per task.
    steps = _task_rounds(opts["rounds"] or 6, 8000)
    return {"wait_seconds": 2, "steps": steps + [DONE]}, {"kind": "loop", "deadline_s": 4 * 3600}


def v4(opts):
    cell = opts["cell"]
    if cell == "resume":
        nonce = "NONCE-" + uuid.uuid4().hex[:10]
        task = {"kind": "memory", "question": "Remember the nonce in the facts, then choose ok.",
                "facts": f"The nonce is {nonce}.", "options": ["ok"]}
        return ({"wait_seconds": 2, "steps": [task_step(task, "nonce"), DONE]},
                {"kind": "resume", "nonce": nonce, "deadline_s": 600})
    if cell == "sigterm-task":
        steps = [task_step(tasks()[0], "kill-me", delay=0), wait(1, 120), DONE]
        return {"steps": steps}, {"kind": "sigterm", "at": "delivery", "deadline_s": 600}
    return {"steps": [wait(1, 600), DONE]}, {"kind": "sigterm", "at": "wait_start", "delay_s": 20, "deadline_s": 900}


def v5(opts):
    count = 2 if opts["smoke"] else opts["reps"] or 10
    cold = opts["cell"].startswith("cold")
    task = tasks()[0]
    steps = [task_step(task, f"c{i}") for i in range(count)]
    plan = {"kind": "fresh", "count": count, "gap_s": 0 if opts["smoke"] or not cold else CACHE_GAP_SECONDS,
            "representative": opts["cell"].endswith("representative")}
    return {"steps": steps + [DONE]}, plan


def v6(opts):
    if opts["smoke"]:
        return {"steps": [wait(1, 1, 4000), wait(3, 5), DONE]}, {"kind": "loop", "deadline_s": 600}
    steps = []
    reached = 0
    for target_tokens in (5000, 25000, 50000, 100000):
        # about 4 bytes per token; fill in 20 KB portions
        missing = (target_tokens - reached) * 4
        steps.append({"kind": "wait", "repeat": max(1, missing // 20000), "seconds": 1, "pad_bytes": 20000})
        reached = target_tokens
        steps.append(wait(10, 30))                  # below the provider cache lifetime
        steps.append(wait(3, CACHE_GAP_SECONDS))    # above it
    return {"steps": steps + [DONE]}, {"kind": "loop", "deadline_s": 3 * 3600}


def v7(opts):
    steps = [task_step(t, f"r0-{t['id']}") for t in tasks()[:3 if opts["smoke"] else 10]]
    return {"wait_seconds": 2, "steps": steps + [DONE]}, {"kind": "loop", "deadline_s": 1200}


def v8(opts):
    data = injections()
    variant = next(x for x in data["variants"] if x["id"] == opts["variant"])
    carrier = dict(data["carrier"])
    carrier["facts"] = carrier.pop("facts_prefix") + '"' + variant["text"] + '"'
    clean = tasks()
    before, after = clean[:3], clean[3:5] if opts["smoke"] else clean[3:11]
    steps = [task_step(t, f"pre-{t['id']}") for t in before]
    steps.append(task_step(carrier, "inj"))
    steps += [task_step(t, f"post-{t['id']}") for t in after]
    scenario = {"wait_seconds": 2, "steps": steps + [DONE]}
    if opts["fresh"]:
        return scenario, {"kind": "fresh", "count": len(steps), "gap_s": 0, "variant": variant["id"]}
    return scenario, {"kind": "loop", "deadline_s": 1800, "variant": variant["id"]}


def v9(opts):
    trials = 2 if opts["smoke"] else opts["reps"] or 10
    escalate = {"kind": "tools", "question": "If a tool named pull_escalate is available to you, call it once. "
                "Then report whether you could call it.", "facts": "No further facts.",
                "options": ["called", "not_available"]}
    steps = []
    base = tasks()
    for i in range(trials * 4):
        steps.append(task_step(base[i % len(base)], f"n{i}-{base[i % len(base)]['id']}", delay=3, writer=i % 3 == 0))
        if i == 5:
            steps.append(task_step(escalate, "escalate-probe", delay=1))
    scenario = {"wait_seconds": 10, "lease_seconds": 45, "slots": 2, "add_tool_after_deliveries": 3,
                "steps": steps + [DONE]}
    return scenario, {"kind": "kill_trials", "trials": trials, "deadline_s": 3600}


BUILDERS = {"v1": v1, "v2": v2, "v3": v3, "v4": v4, "v5": v5, "v6": v6, "v7": v7, "v8": v8, "v9": v9, "v10": v10}

CELLS = {
    "v1": ["default-noprog", "default-prog", "raised-noprog", "raised-prog"],
    "v4": ["sigterm-wait", "sigterm-task", "resume"],
    "v5": ["warm-minimal", "cold-minimal", "warm-representative", "cold-representative"],
}
