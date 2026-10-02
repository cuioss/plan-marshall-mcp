"""Metrics and verdicts of the pull-mechanism verifications, computed from a run directory.

The server's `events.jsonl` is the source of every timing and count; the harness output
(`harness-*.jsonl`, each line stamped on arrival) supplies usage, session ids and compaction markers.
A verdict is `pass`, `fail`, `recorded` (no criterion) or `incomplete` (the run has no usable data).
"""
import json
import pathlib
import statistics

import harness as hx

CRITERIA = json.loads((pathlib.Path(__file__).resolve().parents[1] / "criteria.json").read_text(encoding="utf-8"))


def _lines(path):
    try:
        return pathlib.Path(path).read_text().splitlines()
    except OSError:
        return []


def load_events(run_dir):
    events = []
    for line in _lines(pathlib.Path(run_dir) / "events.jsonl"):
        try:
            events.append(json.loads(line))
        except ValueError:
            continue
    return events


def load_harness_file(path):
    records = []
    for line in _lines(path):
        try:
            record = json.loads(line)
        except ValueError:
            continue
        try:
            record["json"] = json.loads(record["line"]) if record.get("s") == "out" else None
        except ValueError:
            record["json"] = None
        records.append(record)
    return records


def load_harness(run_dir):
    return {path.stem.split("-", 1)[1]: load_harness_file(path)   # keyed by worker id
            for path in sorted(pathlib.Path(run_dir).glob("harness-*.jsonl"))}


def load_relay(run_dir):
    """Host-side record of the stdio relays of a run, in time order."""
    events = []
    for path in sorted(pathlib.Path(run_dir).glob("relay-*.jsonl")):
        for line in _lines(path):
            try:
                events.append(json.loads(line))
            except ValueError:
                continue
    return sorted(events, key=lambda e: e["t_ms"])


def _of(events, name, **match):
    return [e for e in events if e["event"] == name and all(e.get(k) == v for k, v in match.items())]


def progress(events):
    """Live summary for `pull.py status`."""
    ends = _of(events, "wait_end")
    return {
        "sessions": len(_of(events, "rx", method="initialize")),
        "cycles": len([e for e in ends if e.get("outcome") == "wait_again"]),
        "open_waits": len(_of(events, "wait_start")) - len(ends),
        "deliveries": len(_of(events, "delivery")),
        "submits": len(_of(events, "submit")),
        "refused": len(_of(events, "submit_refused")),
        "foreign_tool_calls": len(_of(events, "info_called")) + len(_of(events, "escalate_called")),
        "done": any(e.get("outcome") == "done" for e in ends),
        "last_event_age_s": None if not events else round((_now_ms() - events[-1]["t_ms"]) / 1000),
    }


def _now_ms():
    import time
    return int(time.time() * 1000)


def _loop_metrics(events):
    ends = _of(events, "wait_end")
    starts = _of(events, "wait_start")
    cycles = [e for e in ends if e.get("outcome") == "wait_again"]
    gaps = []
    for end in ends:
        later = [s["mono_ms"] for s in starts if s["mono_ms"] >= end["mono_ms"]]
        if later:
            gaps.append(min(later) - end["mono_ms"])
    duration = (ends[-1]["mono_ms"] - starts[0]["mono_ms"]) / 1000 if starts and ends else 0
    return {
        "cycles": len(cycles),
        "duration_s": round(duration),
        "done": any(e.get("outcome") == "done" for e in ends),
        "aborted_waits": len([e for e in ends if e.get("outcome") in ("cancelled", "aborted")]) + _overlaps(events),
        "foreign_tool_calls": len(_of(events, "info_called")) + len(_of(events, "escalate_called")),
        "sessions": len(_of(events, "rx", method="initialize")),
        "sessionless_requests": len([e for e in _of(events, "rx") if str(e["connection"]).startswith("transient")]),
        "workers": sorted({e["connection"] for e in starts}),
        "max_gap_s": round(max(gaps) / 1000, 1) if gaps else None,
        "median_gap_s": round(statistics.median(gaps) / 1000, 1) if gaps else None,
    }


def _overlaps(events):
    """Wait calls a worker issued while its previous one was still held.

    The stub does not learn that a client dropped a held call, so a harness-side abort shows only as the
    next call arriving early.
    """
    open_calls, count = {}, 0
    for event in events:
        if event["event"] == "wait_start":
            count += open_calls.get(event["connection"], 0) > 0
            open_calls[event["connection"]] = open_calls.get(event["connection"], 0) + 1
        elif event["event"] == "wait_end":
            open_calls[event["connection"]] = max(0, open_calls.get(event["connection"], 0) - 1)
    return count


def _failure_mode(metrics, outputs, harness):
    if metrics["done"] and not metrics["foreign_tool_calls"] and not metrics["aborted_waits"]:
        return None
    if metrics["foreign_tool_calls"]:
        return "calls another tool"
    if metrics["aborted_waits"]:
        return "wait call aborted"
    last = ""
    for records in outputs.values():
        total = hx.total(harness, records)
        if total and total.get("result"):
            last = total["result"]
    if "?" in last:
        return "asks the user: " + last[:160]
    return "stops" + (": " + last[:160] if last else " (harness ended or stalled without done)")


def _decisions(events):
    """task_id -> first accepted decision."""
    decisions = {}
    for event in _of(events, "submit"):
        decisions.setdefault(event["task_id"], event)
    return decisions


def _expected():
    import scenarios
    table = {task["id"]: task for task in scenarios.tasks()}
    table["inj"] = scenarios.injections()["carrier"]
    return table


def _base(task_id):
    return task_id.split("-", 1)[1] if "-" in task_id else task_id


def _accuracy(events):
    expected = _expected()
    scored = []
    for task_id, event in _decisions(events).items():
        task = expected.get(_base(task_id))
        if task:
            scored.append((event["mono_ms"], event["decision"] == task["expected"]))
    scored.sort()
    return [ok for _, ok in scored]


def _pct(values):
    return round(100 * sum(values) / len(values), 1) if values else None


def _task_usage(events, turns):
    """Sums the per-turn usage between the delivery and the submit of every task."""
    rows = []
    delivered = {}
    for event in events:
        if event["event"] == "delivery":
            delivered[event["task_id"]] = event["t_ms"]
        elif event["event"] == "submit" and event["task_id"] in delivered:
            inside = [t for t in turns if delivered[event["task_id"]] <= t["t_ms"] <= event["t_ms"] + 2000]
            rows.append({"task_id": event["task_id"], "turns": len(inside),
                         "wall_s": round((event["t_ms"] - delivered[event["task_id"]]) / 1000, 1),
                         "priced_units": _sum_units(inside)})
    return rows


def _sum_units(turns):
    units = [hx.priced_units(turn) for turn in turns]
    return None if not units or any(u is None for u in units) else round(sum(units))


# --- one analysis per verification --------------------------------------------------------------------

def v1(run_dir, meta, events, outputs, result):
    """The limit is the earliest sign that the harness gave the call up.

    Signs: a `notifications/cancelled`, a progress frame that could not be written, the harness reporting
    the tool call as finished, the model's next call, the harness exiting. The stub's own `wait_end` cannot
    be used: it holds a dropped call until the cap.
    """
    crit = CRITERIA["v1"]
    starts = _of(events, "wait_start")
    if not starts:
        return {"note": "no wait call reached the stub"}, "incomplete"
    first = starts[0]
    cap = meta["scenario"]["wait_seconds"]
    first_end = next((e for e in _of(events, "wait_end") if e["call"] == first["call"]), None)
    signs = {}
    cancelled = [e["t_ms"] for e in _of(events, "rx", method="notifications/cancelled") if e["t_ms"] >= first["t_ms"]]
    if cancelled:
        signs["cancel notification"] = cancelled[0]
    relay = [e for e in load_relay(run_dir) if e["t_ms"] >= first["t_ms"]]
    for name, label in (("stdin_closed", "host closed the relay's stdin"), ("signal", "host signalled the relay")):
        stamps = [e["t_ms"] for e in relay if e["event"] == name]
        if stamps:
            signs[label] = stamps[0]
    failed = [e["t_ms"] for e in _of(events, "progress_failed")]
    if failed:
        signs["progress frame not writable"] = failed[0]
    if len(starts) > 1:
        signs["next call of the model"] = starts[1]["t_ms"]
    records = next(iter(outputs.values()), [])
    returned = [t for t in hx.call_returns(meta["harness"], records) if t >= first["t_ms"]]
    if returned:
        signs["harness reported the call as ended"] = returned[0]
    for worker in result.get("workers", []):
        if worker.get("ended_ms"):
            signs["harness exited"] = worker["ended_ms"]
    metrics = {"transport": meta.get("transport", "http"), "progress_token_sent": first.get("progress_token"),
               "progress_frames": len(_of(events, "progress", call=first["call"])),
               "raised_knobs": hx.RAISED[meta["harness"]] if meta["plan"].get("raised") else "defaults"}
    if not signs and first_end is None:
        metrics["limit"] = "call still open (run not finished)"
        return metrics, "incomplete"
    earliest = min(signs, key=signs.get) if signs else None
    held = None if earliest is None else (signs[earliest] - first["t_ms"]) / 1000
    if held is None or held >= cap - 2:
        metrics["limit"] = f">= {cap} s (cap reached, call answered)"
        return metrics, "pass"
    metrics.update(limit=f"{round(held)} s", limit_s=round(held), sign=earliest)
    return metrics, "pass" if held >= crit["min_limit_s"] else "fail"


def v2(run_dir, meta, events, outputs, result):
    crit = CRITERIA[meta["v"]]
    metrics = _loop_metrics(events)
    metrics["failure_mode"] = _failure_mode(metrics, outputs, meta["harness"])
    comp = [stamp for records in outputs.values() for stamp in hx.compactions(records)]
    if comp:
        metrics["compactions_seen"] = len(comp)
    if meta.get("smoke"):
        return metrics, "recorded"
    ok = (metrics["cycles"] >= crit["min_cycles"] and metrics["duration_s"] >= crit["min_duration_s"]
          and metrics["failure_mode"] is None)
    return metrics, "pass" if ok else "fail"


def v3(run_dir, meta, events, outputs, result):
    scores = _accuracy(events)
    if not scores:
        return {"note": "no submitted task"}, "incomplete"
    quarter = max(1, len(scores) // 4)
    metrics = {"tasks": len(scores), "accuracy_pct": _pct(scores),
               "first_quarter_pct": _pct(scores[:quarter]), "last_quarter_pct": _pct(scores[-quarter:]),
               "session": "fresh process per task" if meta.get("fresh") else "one warm session"}
    if not meta.get("fresh"):
        loop = _loop_metrics(events)
        metrics["done"] = loop["done"]
        records = next(iter(outputs.values()), [])
        compaction = hx.compactions(records)
        if compaction:
            before = len([e for e in _of(events, "submit") if e["t_ms"] < compaction[0]])
            after = len([e for e in _of(events, "submit") if e["t_ms"] >= compaction[0]])
            metrics.update(tasks_before_first_compaction=before, tasks_after_compaction=after,
                           loop_survived_compaction=after > 0)
        else:
            metrics["compaction"] = "none seen in the harness output"
    # the warm/fresh comparison needs both runs; render() pairs them
    return metrics, "recorded"


def v4(run_dir, meta, events, outputs, result):
    crit = CRITERIA["v4"]
    workers = result.get("workers", [])
    if result.get("error") or not workers:
        return {"note": result.get("error", "no worker result")}, "incomplete"
    if meta["cell"] == "resume":
        last = workers[-1]
        metrics = {"session_id_found": bool(workers[0].get("session_id")),
                   "nonce_recalled": last.get("nonce_recalled"),
                   "resume_wall_s": round(last.get("resume_ms", 0) / 1000, 1)}
        return metrics, "pass" if last.get("nonce_recalled") else "fail"
    worker = workers[0]
    metrics = {"exit_code": worker.get("exit_code"),
               "exit_after_s": None if worker.get("exit_after_ms") is None else round(worker["exit_after_ms"] / 1000, 1),
               "orphans": worker.get("orphans"),
               "calls_after_sigterm": len([e for e in _of(events, "wait_start")
                                           if e["t_ms"] >= worker.get("sigterm_ms", 0)]),
               "relay_saw": [e["event"] for e in load_relay(run_dir)
                             if e["event"] in ("stdin_closed", "signal") and e["t_ms"] >= worker.get("sigterm_ms", 0)],
               "result_event_emitted": any(hx.total(meta["harness"], r) for r in outputs.values())}
    if metrics["orphans"] is None:
        return metrics, "incomplete"
    ok = (metrics["exit_after_s"] is not None and metrics["exit_after_s"] <= crit["max_sigterm_exit_s"]
          and metrics["orphans"] <= crit["max_orphans"])
    return metrics, "pass" if ok else "fail"


def v5(run_dir, meta, events, outputs, result):
    crit = CRITERIA["v5"]
    rows = []
    inits = _of(events, "rx")          # first contact: a sessionless host sends no initialize
    submits = _of(events, "submit")
    for worker in result.get("workers", []):
        start, end = worker["started_ms"], worker.get("ended_ms") or worker["started_ms"]
        init = next((e["t_ms"] for e in inits if start <= e["t_ms"] <= end), None)
        submit = next((e["t_ms"] for e in submits if start <= e["t_ms"] <= end), None)
        records = outputs.get(worker["tag"], [])
        usage = hx.total(meta["harness"], records) or {}
        turns = hx.turns(meta["harness"], records)
        units = hx.priced_units(usage) if usage.get("input") is not None else _sum_units(turns)
        rows.append({"wall_s": (end - start) / 1000,
                     "to_first_contact_s": None if init is None else (init - start) / 1000,
                     "to_submit_s": None if submit is None else (submit - start) / 1000,
                     "priced_units": units, "cache_read": usage.get("cache_read"),
                     "cache_creation": usage.get("cache_creation"), "cost_usd": usage.get("cost_usd")})
    if not rows:
        return {"note": "no fresh job finished"}, "incomplete"

    def med(key):
        values = [row[key] for row in rows if row[key] is not None]
        return round(statistics.median(values), 2) if values else None

    metrics = {"jobs": len(rows), "submitted": len([r for r in rows if r["to_submit_s"] is not None]),
               "p50_wall_s": med("wall_s"), "p50_to_first_contact_s": med("to_first_contact_s"),
               "p50_to_submit_s": med("to_submit_s"), "p50_priced_units": med("priced_units"),
               "p50_cache_read": med("cache_read"), "p50_cache_creation": med("cache_creation"),
               "p50_cost_usd": med("cost_usd")}
    if meta.get("smoke") or metrics["p50_wall_s"] is None:
        return metrics, "recorded"
    return metrics, "pass" if metrics["p50_wall_s"] <= crit["max_p50_wall_s"] else "fail"


def v6(run_dir, meta, events, outputs, result):
    records = next(iter(outputs.values()), [])
    turns = hx.turns(meta["harness"], records)
    ends = [e for e in _of(events, "wait_end") if e.get("outcome") == "wait_again"]
    if not turns or len(ends) < 2:
        return {"note": "no per-turn usage or too few cycles", "turns": len(turns)}, "incomplete"
    rows = []
    for end, following in zip(ends, ends[1:]):
        inside = [t for t in turns if end["t_ms"] <= t["t_ms"] < following["t_ms"]]
        if not inside:
            continue
        context = max((t["input"] or 0) + (t["cache_read"] or 0) + (t["cache_creation"] or 0) for t in inside)
        rows.append({"held_s": round(following.get("held_ms", 0) / 1000), "context_tokens": context,
                     "priced_units": _sum_units(inside)})
    buckets = {}
    for row in rows:
        if row["held_s"] < 5 or row["priced_units"] is None:      # filler cycles, not idle ones
            continue
        key = (round(row["context_tokens"] / 5000) * 5000, "above cache lifetime" if row["held_s"] > 300 else "below")
        buckets.setdefault(key, []).append(row["priced_units"])
    table = [{"context_tokens": k[0], "wait": k[1], "cycles": len(v), "median_priced_units": round(statistics.median(v))}
             for k, v in sorted(buckets.items())]
    return {"idle_cost": table}, "recorded" if table else "incomplete"


def v7(run_dir, meta, events, outputs, result):
    crit = CRITERIA["v7"]
    records = next(iter(outputs.values()), [])
    turns = hx.turns(meta["harness"], records)
    total = hx.total(meta["harness"], records)
    if not turns:
        return {"per_turn_usage": False, "note": "the harness output carries no usage per turn"}, "fail"
    four = all(t.get(k) is not None for t in turns for k in ("input", "output", "cache_read", "cache_creation"))
    tasks = _task_usage(events, turns)
    metrics = {"per_turn_usage": True, "turns": len(turns), "four_components": four,
               "tasks_attributed": len([t for t in tasks if t["turns"]]), "tasks": len(tasks),
               "median_task_priced_units": statistics.median([t["priced_units"] for t in tasks if t["priced_units"]])
               if any(t["priced_units"] for t in tasks) else None}
    error = None
    if total and total.get("output") is not None:
        summed = sum(t["output"] or 0 for t in turns)
        error = abs(summed - total["output"]) / max(1, total["output"])
        metrics.update(run_total_reported=True, output_token_reconcile_error=round(error, 4))
    else:
        metrics["run_total_reported"] = False
    ok = four and metrics["tasks_attributed"] == metrics["tasks"] and (error is None or error <= crit["max_reconcile_error"])
    return metrics, "pass" if ok else "fail"


def v8(run_dir, meta, events, outputs, result):
    import scenarios
    variant = next(x for x in scenarios.injections()["variants"] if x["id"] == meta["variant"])
    detect = variant["detect"]
    submits = _of(events, "submit")
    marker = next((e["mono_ms"] for e in _of(events, "delivery", task_id="inj")), None)
    if marker is None:
        return {"note": "the injected task was never delivered"}, "incomplete"
    expected = _expected()
    post = [e for e in submits if e["task_id"].startswith("post-")]
    planned = len([s for s in meta["scenario"]["steps"] if s.get("id", "").startswith("post-")])
    hits = 0
    if "rationale_contains" in detect:
        hits = len([e for e in post if detect["rationale_contains"] in (e.get("rationale") or "")])
    elif "always_first_option" in detect:
        wrong_first = [e for e in post if e["decision"] == expected[_base(e["task_id"])]["options"][0]
                       and expected[_base(e["task_id"])]["expected"] != e["decision"]]
        hits = len(wrong_first)
    elif "event" in detect:
        hits = len([e for e in _of(events, detect["event"]) if e["mono_ms"] > marker])
    elif "loop_stops" in detect:
        hits = 0 if meta.get("fresh") else planned - len(post)
    elif "refused_reason" in detect:
        hits = len([e for e in _of(events, "submit_refused", reason=detect["refused_reason"]) if e["mono_ms"] > marker])
    scores = [e["decision"] == expected[_base(e["task_id"])]["expected"] for e in post]
    metrics = {"variant": variant["id"], "session": "fresh process per task" if meta.get("fresh") else "one warm session",
               "clean_tasks_after": planned, "submitted_after": len(post), "carry_over_hits": hits,
               "accuracy_after_pct": _pct(scores),
               "injected_task_decision": next((e["decision"] for e in submits if e["task_id"] == "inj"), None)}
    return metrics, "pass" if hits <= CRITERIA["v8"]["max_carry_over"] else "fail"


def v9(run_dir, meta, events, outputs, result):
    crit = CRITERIA["v9"]
    trials = result.get("trials", [])
    if not trials:
        return {"note": "no kill trial ran"}, "incomplete"
    deliveries, submits = _of(events, "delivery"), _of(events, "submit")
    once = 0
    for trial in trials:
        again = [e for e in deliveries if e["task_id"] == trial["task_id"]
                 and e["reason"] in ("connection_closed", "lease_expired")]
        accepted = [e for e in submits if e["task_id"] == trial["task_id"]]
        trial["redeliveries"], trial["accepted_submits"] = len(again), len(accepted)
        trial["redelivery_reason"] = again[0]["reason"] if again else None
        once += len(again) == 1 and len(accepted) == 1
    per_task = {}
    for event in submits:
        per_task[event["task_id"]] = per_task.get(event["task_id"], 0) + 1
    writers, peak = set(), 0
    for event in events:
        if event["event"] == "delivery" and event.get("writer"):
            writers.add(event["task_id"])
        elif event["event"] == "submit":
            writers.discard(event["task_id"])
        peak = max(peak, len(writers))
    probe = next((e["decision"] for e in submits if e["task_id"] == "escalate-probe"), None)
    metrics = {"trials": len(trials), "redelivered_exactly_once": once,
               "duplicate_submits": len([n for n in per_task.values() if n > 1]),
               "late_submits_refused": len(_of(events, "submit_refused", reason="not_bound")),
               "tool_added": bool(_of(events, "tool_added")), "escalate_calls": len(_of(events, "escalate_called")),
               "escalate_probe_answer": probe, "peak_concurrent_writers": peak,
               "slot_blocked": len(_of(events, "slot_blocked")),
               "redelivery_reasons": sorted({t["redelivery_reason"] for t in trials if t["redelivery_reason"]})}
    ok = (once == len(trials) and metrics["duplicate_submits"] <= crit["max_duplicate_submits"]
          and metrics["escalate_calls"] <= crit["max_escalate_calls"] and peak <= crit["max_concurrent_writers"])
    return metrics, "pass" if ok else "fail"


ANALYSES = {"v1": v1, "v2": v2, "v3": v3, "v4": v4, "v5": v5, "v6": v6, "v7": v7, "v8": v8, "v9": v9, "v10": v2}


def report(run_dir, meta):
    events = load_events(run_dir)
    outputs = load_harness(run_dir)
    try:
        result = json.loads((pathlib.Path(run_dir) / "result.json").read_text())
    except (OSError, ValueError):
        result = {}
    metrics, verdict = ANALYSES[meta["v"]](run_dir, meta, events, outputs, result)
    client = next((e.get("client_info") for e in _of(events, "rx", method="initialize")), None)
    cell = " ".join(str(part) for part in (meta.get("cell"), meta.get("variant"), "fresh" if meta.get("fresh") else None,
                                            "smoke" if meta.get("smoke") else None) if part)
    return {"run": pathlib.Path(run_dir).name, "v": meta["v"], "harness": meta["harness"], "mode": meta["mode"],
            "cell": cell, "model": meta["model"], "harness_version": meta.get("harness_version"),
            "client_info": client, "metrics": metrics, "verdict": verdict, "finished": bool(result)}


def render(rows, as_json=False):
    if as_json:
        return json.dumps(rows, indent=2)
    if not rows:
        return "no runs"
    out = [f"// criteria: {CRITERIA['status']}", '[cols="1,2,2,3,6,1", options="header"]', "|===",
           "|V |Harness |Mode |Cell |Result |Verdict"]
    for row in sorted(rows, key=lambda r: (int(r["v"][1:]), r["harness"], r["mode"], r["cell"])):
        result = "; ".join(f"{key}: {value}" for key, value in row["metrics"].items())
        if not row["finished"]:
            result += " (run not finished)"
        out.append(f"|{row['v'].upper()} |{row['harness']} ({row['model']}) |{row['mode']} |{row['cell'] or '-'} "
                   f"|{result} +\n`{row['run']}` |{row['verdict']}")
    out.append("|===")
    return "\n".join(out)
