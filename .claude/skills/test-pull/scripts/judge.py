"""Judges the open answers of E7 against the operator's references.

A review answer is scored by the seeded defects it found (recall), with its false findings counted apart;
a diff without seeded defects scores 1 only for an empty answer. A re-planning answer is scored by the share
of the reference's essential properties it meets. The judge is a fresh job without tools (`claude -p` with
`--tools ""`), one per answer; its output is stored with the score, so every judgement can be read back.
Closed answers need no judge: they are compared with the reference by the report.
"""
import json
import pathlib
import re
import subprocess

import harness as hx

JUDGE_MODEL = "claude-sonnet-5-5"

PROMPT = """You grade one answer of an AI worker against a reference that a human operator set. Grade strictly
and only against the reference; do not add expectations of your own.

Task given to the worker:
{question}

Facts the worker saw:
{facts}

Reference:
{reference}

The worker's answer:
{answer}

{rule}

Reply with JSON only: {{"items_met": [<ids or short names of the reference items the answer meets>],
"items_total": <number of reference items>, "false_findings": <number of findings in the answer that the
reference does not contain and that are not real defects>, "score": <number from 0 to 1>, "reason": "<one sentence>"}}"""

RULES = {
    "review": "Each reference item is one seeded defect. An item is met when the answer names that defect at "
              "about the right place. score = items met / items total; if the reference has no defect, "
              "score = 1 when the answer reports none, else 0.",
    "replan": "Each reference item is one essential property of a good revised plan. An item is met when the "
              "answer's plan has that property. score = items met / items total.",
}


def judge(fixture, reference, answer, ws):
    kind = "replan" if fixture["kind"] == "replan" else "review"
    prompt = PROMPT.format(question=fixture["question"], facts=fixture["facts"][:6000],
                           reference=json.dumps(reference, indent=1), answer=answer[:8000], rule=RULES[kind])
    ws = pathlib.Path(ws)
    ws.mkdir(parents=True, exist_ok=True)
    empty = ws / "no-mcp.json"
    empty.write_text(json.dumps({"mcpServers": {}}))
    done = subprocess.run(["claude", "-p", prompt, "--model", JUDGE_MODEL, "--tools", "", "--mcp-config",
                           str(empty), "--strict-mcp-config", "--output-format", "json", "--no-session-persistence"],
                          capture_output=True, text=True, cwd=ws, env=hx.clean_env(), timeout=600,
                          stdin=subprocess.DEVNULL)
    try:
        reply = json.loads(done.stdout).get("result") or ""
        verdict = json.loads(re.search(r"\{.*\}", reply, re.S).group(0))
        verdict["score"] = max(0.0, min(1.0, float(verdict["score"])))
        return verdict
    except (ValueError, AttributeError, KeyError, TypeError):
        return {"score": None, "error": (done.stdout + done.stderr)[-400:]}


def judge_run(run_dir, ws_root):
    """Judges every open answer of an E7 run that has no judgement yet; returns the judgements."""
    import analyze
    import scenarios
    run_dir = pathlib.Path(run_dir)
    meta = json.loads((run_dir / "meta.json").read_text())
    fixtures = {f["id"]: f for f in scenarios.e7_fixtures(meta["plan"]["e7_kind"])["fixtures"]}
    path = run_dir / "judgements.json"
    judgements = json.loads(path.read_text()) if path.exists() else {}
    for task_id, event in analyze._decisions(analyze.load_events(run_dir)).items():
        fixture = fixtures.get(task_id)
        if fixture is None or fixture.get("options") or judgements.get(task_id, {}).get("score") is not None:
            continue
        reference, confirmed = analyze._reference(fixture)
        verdict = judge(fixture, reference, event["decision"], pathlib.Path(ws_root) / run_dir.name / task_id)
        verdict["reference_confirmed"] = confirmed
        judgements[task_id] = verdict
        path.write_text(json.dumps(judgements, indent=1))
    return judgements
