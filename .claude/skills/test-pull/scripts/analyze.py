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


# --- evaluation E1 to E13 -----------------------------------------------------------------------------

def load_runtime(run_dir):
    """The record of the job runtime (supervisor.jsonl)."""
    records = []
    for line in _lines(pathlib.Path(run_dir) / "supervisor.jsonl"):
        try:
            records.append(json.loads(line))
        except ValueError:
            continue
    return records


def _quantile(values, q):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(q * len(ordered)))]


def _open_at_end(result, events, run_dir=None):
    """Tasks the stub still held when the run ended: leased, or given back and waiting for a successor.

    `state_final` is read after every worker ended. Older runs have only the last snapshot before the close
    (`state_at_end`); a task offered after the close began counts as held there.
    """
    state = result.get("state_final") or result.get("state_at_end") or {}
    held = {lease["task_id"] for lease in state.get("leases", [])}
    held |= {entry["task_id"] for entry in state.get("waiting", []) if entry.get("reason") != "new"}
    if "state_final" not in result and run_dir is not None:
        closing = [r["t_ms"] for r in load_runtime(run_dir) if r["event"] == "detect"
                   and r["reason"] in ("shutdown", "ended")]
        if closing:
            start = min(closing) - 21000          # the close waits up to 20 s for workers to leave
            held |= {e["task_id"] for e in _of(events, "offer") if e["t_ms"] >= start}
    return held


def _exactly_once(events, result=None, run_dir=None):
    """Tasks by their number of accepted submits; a task the stub still held when the run ended is left out."""
    offered = {e["task_id"] for e in _of(events, "offer")}
    counts = {task_id: 0 for task_id in offered}
    for event in _of(events, "submit"):
        counts[event["task_id"]] = counts.get(event["task_id"], 0) + 1
    held = _open_at_end(result or {}, events, run_dir)
    open_at_end = [t for t, n in counts.items() if n == 0 and t in held]
    return {"offered": len(offered), "submitted_once": len([n for n in counts.values() if n == 1]),
            "duplicates": len([n for n in counts.values() if n > 1]),
            "never_submitted": len([t for t, n in counts.items() if n == 0 and t not in open_at_end]),
            "open_at_end": len(open_at_end)}


def _stale_accepted(events):
    """Accepted submits of a generation that was fenced before the submit."""
    fenced, count = {}, 0
    for event in events:
        if event["event"] == "fenced":
            fenced[event["connection"]] = max(fenced.get(event["connection"], -1), event.get("generation", -1))
        elif event["event"] == "submit" and event.get("generation", 0) <= fenced.get(event["connection"], -1):
            count += 1
    return count


def _offer_latency(events):
    """Seconds from a task becoming due (or returning to the queue) to the offer that led to its submit."""
    due = {e["task_id"]: e["t_ms"] for e in _of(events, "release_due")}
    waits = {}
    for event in events:
        if event["event"] == "offer" and event["task_id"] in due:
            waits.setdefault(event["task_id"], (event["t_ms"] - due[event["task_id"]]) / 1000)
    return waits


def _usage_total(meta, outputs, workers):
    """Priced units and cost over all processes of a run; a killed process reports no total."""
    units, cost, unmeasured = 0, 0.0, 0
    for worker in workers:
        records = outputs.get(worker["tag"], [])
        summed = _sum_units(hx.turns(worker.get("harness", meta["harness"]), records))
        total = hx.total(worker.get("harness", meta["harness"]), records) or {}
        if summed is None:
            unmeasured += 1
        else:
            units += summed
        cost += total.get("cost_usd") or 0
    return {"priced_units": units, "cost_usd_reported": round(cost, 3), "processes": len(workers),
            "processes_without_usage": unmeasured}


def e1(run_dir, meta, events, outputs, result):
    crit = CRITERIA["e1"]
    offers, acks = _of(events, "offer"), _of(events, "ack")
    if not offers:
        return {"note": "no task was offered"}, "incomplete"
    latencies = [e["latency_ms"] / 1000 for e in acks]
    once = _exactly_once(events, result, run_dir)
    unacked_submits = len([e for e in _of(events, "submit") if e.get("acked") is False])
    missed = len(_of(events, "ack_missed"))
    runtime = load_runtime(run_dir)
    lost = len([r for r in runtime if r["event"] == "detect" and r["reason"] in ("exit", "silence", "ack_missed")])
    turns = [turn for tag, records in outputs.items() for turn in hx.turns(meta["harness"], records)]
    usage = [row for row in _task_usage([dict(e, event="delivery") if e["event"] == "offer" else e for e in events],
                                        sorted(turns, key=lambda t: t["t_ms"])) if row["priced_units"]]
    longest = max(latencies) if latencies else None
    p999 = _quantile(latencies, 0.999)
    metrics = {"variant": meta["cell"], "offers": len(offers), "acks": len(acks),
               "ack_p50_s": round(statistics.median(latencies), 2) if latencies else None,
               "ack_p99_s": _quantile(latencies, 0.99), "ack_p999_s": p999, "ack_max_s": longest,
               "submits_without_ack": unacked_submits, "ack_missed": missed, "workers_lost": lost,
               "deadline_proposed_s": None if p999 is None else round(max(p999 * crit["deadline_factor"], longest)
                                                                      + crit["deadline_margin_s"], 1),
               "median_task_priced_units": statistics.median([r["priced_units"] for r in usage]) if usage else None,
               "median_task_wall_s": statistics.median([r["wall_s"] for r in usage]) if usage else None,
               **once, **_usage_total(meta, outputs, result.get("workers", []))}
    if meta.get("smoke"):
        return metrics, "recorded"
    ok = (unacked_submits + missed <= crit["max_tasks_without_ack"] and once["duplicates"] == 0
          and once["never_submitted"] == 0)
    return metrics, "pass" if ok else "fail"


def e2(run_dir, meta, events, outputs, result):
    crit = CRITERIA["e2"]
    trials = result.get("trials") or []
    if not trials:
        return {"note": "no fault was injected"}, "incomplete"
    runtime = load_runtime(run_dir)
    silence_s = meta["scenario"]["wait_seconds"] + meta["plan"]["silence_grace_s"]
    deadline_s = {"exit": 0, "silence": silence_s, "ack_missed": meta["scenario"]["ack_deadline_seconds"]}
    fault = meta["plan"]["fault"]
    late, early, slow_release, by_expiry, detected = 0, 0, 0, 0, []
    for trial in trials:
        mine = [r for r in runtime if r.get("worker") == trial["worker"] and r.get("generation") == trial["generation"]
                and r["t_ms"] >= trial["injected_ms"] - 500]
        detect = next((r for r in mine if r["event"] == "detect" and r["reason"] in deadline_s), None)
        sent = next((r for r in mine if r["event"] == "fence_sent"), None)
        fenced = next((e for e in _of(events, "fenced", connection=trial["worker"])
                       if e.get("generation") == trial["generation"]), None)
        if fault == "stop-short":
            early += detect is not None and detect["t_ms"] <= trial.get("submitted_ms", trial["settled_ms"])
            continue
        if detect is None:
            late += "recovered_ms" not in trial      # the host restarted its relay and the worker went on
            continue
        # the deadline of a signal runs from the last sign of life, a process exit is seen at once
        base = detect["last_event_ms"] if detect["reason"] in ("silence", "ack_missed") else trial["injected_ms"]
        after = (detect["t_ms"] - base) / 1000
        trial.update(detected_by=detect["reason"], detected_after_s=round(after, 1))
        detected.append(detect["reason"])
        if fault in ("kill-offer", "kill-exec") or detect["reason"] != "exit":
            late += after > deadline_s[detect["reason"]] + crit["detection_margin_s"]
        if detect["reason"] == "silence":
            early += after < deadline_s["silence"]
        if sent and fenced:
            slow_release += (fenced["t_ms"] - sent["t_ms"]) / 1000 > crit["max_release_s"]
        again = [e for e in _of(events, "offer", task_id=trial.get("task_id")) if e["t_ms"] > detect["t_ms"]]
        by_expiry += bool(again) and again[0]["reason"] not in ("fenced", "superseded", "ack_missed")
    once = _exactly_once(events, result, run_dir)
    tasks = [t for t in trials if t.get("task_id")]
    metrics = {"fault": fault, "trials": len(trials),
               "trial_tasks_submitted": len([t for t in tasks if "submitted_ms" in t]), "trial_tasks": len(tasks),
               "detected_by": sorted(set(detected)), "detected_late_or_never": late, "replaced_early": early,
               "max_detected_after_s": max([t["detected_after_s"] for t in trials if "detected_after_s" in t],
                                           default=None),
               "recovered_without_replacement": len([t for t in trials if "recovered_ms" in t]),
               "release_slower_than_limit": slow_release, "released_by_expiry": by_expiry,
               "stale_submits_refused": len([e for e in _of(events, "stale_refused") if e.get("call") == "submit"]),
               "stale_submits_accepted": _stale_accepted(events), **once}
    if meta.get("smoke"):
        return metrics, "recorded"
    ok = (once["duplicates"] == 0 and once["never_submitted"] == 0 and late == 0 and early == 0
          and slow_release == 0 and by_expiry == 0 and metrics["stale_submits_accepted"] == 0
          and metrics["trial_tasks_submitted"] == metrics["trial_tasks"])
    return metrics, "pass" if ok else "fail"


def e3(run_dir, meta, events, outputs, result):
    crit = CRITERIA["e3"]
    runtime = load_runtime(run_dir)
    detects = [r for r in runtime if r["event"] == "detect"]
    reasons = {}
    for record in detects:
        reasons[record["reason"]] = reasons.get(record["reason"], 0) + 1
    waits = _offer_latency(events)
    budget = [r["context_tokens"] for r in detects if r["reason"] == "budget"]
    once = _exactly_once(events, result, run_dir)
    planned = len([s for s in meta["scenario"]["steps"] if s["kind"] == "task"])
    self_stops = reasons.get("exit", 0) + reasons.get("silence", 0)
    metrics = {"cell": meta["cell"], "tasks_planned": planned, "tasks_submitted": len(_of(events, "submit")),
               "self_stops": self_stops, "ended_by_runtime": {k: v for k, v in reasons.items()
                                                              if k in ("idle", "budget", "recycle")},
               "generations": len([r for r in runtime if r["event"] == "spawn"]),
               "max_task_wait_s": round(max(waits.values()), 1) if waits else None,
               "median_task_wait_s": round(statistics.median(waits.values()), 1) if waits else None,
               "budget_recycles_at_tokens": budget, "duplicates": once["duplicates"],
               **_usage_total(meta, outputs, result.get("workers", []))}
    if result.get("note"):
        metrics["note"] = result["note"]
    if meta.get("smoke") or meta["cell"] == "A-norecycle":
        return metrics, "recorded"          # variant A is the reference without recycling
    limit = meta["scenario"]["wait_seconds"] + crit["task_wait_margin_s"]
    ok = self_stops == 0 and metrics["tasks_submitted"] == planned and once["duplicates"] == 0
    if meta["cell"] == "budget-fill":
        ok = ok and bool(budget) and max(budget) <= crit["max_budget_recycle_tokens"]
    else:
        ok = ok and waits and max(waits.values()) <= limit
    return metrics, "pass" if ok else "fail"


def e4(run_dir, meta, events, outputs, result):
    crit = CRITERIA["e4"]
    submits = _of(events, "submit")
    if not submits:
        return {"note": "no task was submitted"}, "incomplete"
    once = _exactly_once(events, result, run_dir)
    waits = _offer_latency(events)
    due = {e["task_id"]: e["t_ms"] for e in _of(events, "release_due")}
    accepted = {}
    for offer in _of(events, "offer"):
        accepted[offer["task_id"]] = offer          # the last offer is the one that was submitted
    by_session = [e for e in submits if e["connection"] == "tui"]
    missed = {t: (o["t_ms"] - due[t]) / 1000 for t, o in accepted.items() if o["connection"] != "tui" and t in due}
    held = [e.get("held_ms", 0) / 1000 for e in _of(events, "wait_end", connection="tui")]
    metrics = {"tasks": len(submits), "taken_by_session": len(by_session),
               "session_share_pct": _pct([e["connection"] == "tui" for e in submits]),
               "ack_missed_by_session": len(_of(events, "ack_missed", connection="tui")),
               "max_delay_of_missed_task_s": round(max(missed.values()), 1) if missed else 0,
               "max_first_offer_wait_s": round(max(waits.values()), 1) if waits else None,
               "longest_session_wait_call_s": round(max(held), 1) if held else None,
               "operator_input_max_queued_s": result.get("operator", {}).get("input_max_queued_s",
                                                                             "to be noted by the operator"),
               **once}
    if meta.get("smoke"):
        return metrics, "recorded"
    limit = meta["scenario"]["ack_deadline_seconds"] + crit["delay_margin_s"]
    queued = metrics["operator_input_max_queued_s"]
    if not isinstance(queued, (int, float)):
        return metrics, "incomplete"
    ok = (once["duplicates"] == 0 and once["never_submitted"] == 0 and metrics["max_delay_of_missed_task_s"] <= limit
          and queued <= meta["scenario"]["wait_seconds"])
    return metrics, "pass" if ok else "fail"


def e5(run_dir, meta, events, outputs, result):
    crit = CRITERIA["e5"]
    opened = _of(events, "consult_open")
    if not opened:
        return {"note": "no consultation was opened"}, "incomplete"
    answers, delivered = _of(events, "consult_answer"), _of(events, "consult_delivered")
    per_id = {}
    for event in answers:
        per_id[event["consultation_id"]] = per_id.get(event["consultation_id"], 0) + 1
    askers = {e["consultation_id"]: e["connection"] for e in opened}
    right = [e for e in delivered if askers.get(e["consultation_id"]) == e["connection"]]
    latency = []
    for event in delivered:
        start = next((o["t_ms"] for o in opened if o["consultation_id"] == event["consultation_id"]), None)
        if start:
            latency.append((event["t_ms"] - start) / 1000)
    final = [e for e in _of(events, "submit") if not e["task_id"].startswith("c-")]
    referenced = [e for e in final if e.get("consultation_ref") == "ok"]
    runtime = load_runtime(run_dir)
    asker_lost = len([r for r in runtime if r["event"] == "detect" and str(r.get("worker", "")).startswith("w")
                      and r["reason"] in ("exit", "silence", "ack_missed")])
    planned = len([s for s in meta["scenario"]["steps"] if s["kind"] == "task"])
    metrics = {"consultant": next((s.get("harness", meta["harness"]) for s in meta["plan"]["slots"]
                                   if s["role"] == "consultant"), None),
               "tasks": planned, "consultations": len(opened),
               "answered_exactly_once": len([n for n in per_id.values() if n == 1]),
               "delivered_to_asker": len(right), "askers_lost": asker_lost,
               "final_submits": len(final), "final_submits_referencing": len(referenced),
               "submits_without_consultation": len([e for e in final if e.get("consultation_ref") is None]),
               "latency_p50_s": round(statistics.median(latency), 1) if latency else None,
               "latency_max_s": round(max(latency), 1) if latency else None,
               "progress_frames": len(_of(events, "progress")),
               **_usage_total(meta, outputs, result.get("workers", []))}
    if meta.get("smoke"):
        return metrics, "recorded"
    ok = (len(opened) == planned and metrics["answered_exactly_once"] == planned and len(right) == planned
          and asker_lost <= crit["max_askers_lost"] and len(referenced) == len(final) == planned)
    return metrics, "pass" if ok else "fail"


def e6(run_dir, meta, events, outputs, result):
    crit = CRITERIA["e6"]
    metrics, verdict = e2(run_dir, meta, events, outputs, result)
    relay = load_relay(run_dir)
    identities = [e for e in relay if e["event"] == "identity"]
    binding = {}
    for event in events:
        if event["event"] == "offer":
            binding[event["task_id"]] = (event["connection"], event.get("generation"))
    wrong = len([e for e in _of(events, "submit") if binding.get(e["task_id"]) != (e["connection"], e.get("generation"))])
    held = [e.get("held_ms", 0) / 1000 for e in _of(events, "wait_end")]
    metrics.update(jobs=meta["cell"], relays_started=len(identities),
                   identity_from_environment=len([e for e in identities if e.get("source") == "environment"]),
                   workers_seen=sorted({e["connection"] for e in _of(events, "wait_start")}),
                   submits_bound_to_other_job=wrong,
                   longest_wait_s=round(max(held), 1) if held else None,
                   waits_at_bounded_length=len([h for h in held if h >= meta["scenario"]["wait_seconds"] - 1]),
                   aborted_waits=len([e for e in _of(events, "wait_end") if e.get("outcome") in ("cancelled", "aborted")]))
    if verdict in ("recorded", "incomplete"):
        return metrics, verdict
    ok = (verdict == "pass" and wrong == 0 and metrics["identity_from_environment"] == metrics["relays_started"] > 0
          and (metrics["longest_wait_s"] or 0) < crit["wait_limit_s"] and metrics["aborted_waits"] == 0)
    return metrics, "pass" if ok else "fail"


def e13(run_dir, meta, events, outputs, result):
    crit = CRITERIA["e13"]
    if meta["cell"] == "uri-switch":
        seen = result.get("uri_switch")
        if not seen:
            return {"note": "the test clients did not run"}, "incomplete"
        declaring, plain = seen["declaring"]["skills"], seen["plain"]["skills"]
        metrics = {"declaring_client_inband": len([s for s in declaring if s["inband"]]),
                   "declaring_client_named": len(declaring),
                   "plain_client_inband": len([s for s in plain if s["inband"]]),
                   "resources_read": seen["declaring"]["resources_read_ok"], **seen["relay"]}
        ok = (declaring and plain and metrics["declaring_client_inband"] == 0
              and metrics["plain_client_inband"] == len(plain) and metrics["resources_read"]
              and seen["relay"]["skills_list"] > 0 and seen["relay"]["skills_get_ok"]
              and seen["relay"]["spike_tools_listed"] == 0 and seen["relay"]["spike_call_refused"])
        return metrics, "pass" if ok else "fail"
    import scenarios
    data = json.loads((scenarios.FIXTURES / "skill-tasks.json").read_text(encoding="utf-8"))
    table = {task["id"]: task for task in data["tasks"]}
    decisions = _decisions(events)
    if not decisions:
        return {"note": "no submitted task"}, "incomplete"
    rule = [d["decision"] == table[t]["expected"] for t, d in decisions.items() if t in table]
    # common practice types a dependency bump as chore or build; which of the two varies by reader
    common = [d["decision"] in ("chore", "build", "ci") for t, d in decisions.items() if t in table]
    runtime = load_runtime(run_dir)
    # every moment at which a worker's context may have lost the skill
    resets = [(r["t_ms"], r["worker"], "generation") for r in runtime if r["event"] == "spawn"]
    resets += [(e["t_ms"], e["connection"], "compaction") for e in _of(events, "compacted")]
    resets += [(e["t_ms"], None, "digest") for e in _of(events, "skill_updated")]
    offers = [e for e in _of(events, "offer") if e.get("skills")]
    redelivered, expected = {}, {}
    for stamp, worker, kind in resets:
        following = next((o for o in offers if o["t_ms"] >= stamp and worker in (None, o["connection"])), None)
        if following is None:
            continue
        expected[kind] = expected.get(kind, 0) + 1
        redelivered[kind] = redelivered.get(kind, 0) + (following.get("skills_delivered", 0) > 0)
    metrics = {"cell": meta["cell"], "tasks": len(rule), "rule_rate_pct": _pct(rule),
               "common_practice_pct": _pct(common), "offers_with_skills": len(offers),
               "skill_deliveries": len(_of(events, "skill_delivered")),
               "resets": expected, "redelivered_after_reset": redelivered,
               "skill_missing": len(_of(events, "skill_missing")),
               "skill_reads": len([e for e in _of(events, "skill_read") if e.get("found")]),
               "generations": len([r for r in runtime if r["event"] == "spawn"]),
               **_usage_total(meta, outputs, result.get("workers", []))}
    if meta.get("smoke") or meta["cell"] == "control":
        return metrics, "recorded"          # the control is the reference; render() compares against it
    ok = (metrics["rule_rate_pct"] >= crit["min_rule_rate_pct"] and metrics["skill_missing"] == 0
          and redelivered == expected)
    return metrics, "pass" if ok else "fail"


# --- evaluation Stage 2 ------------------------------------------------------------------------------

def e9(run_dir, meta, events, outputs, result):
    crit = CRITERIA.get("e9", {})
    import scenarios
    labels = {item["id"]: item["label"] for item in scenarios.e9_corpus()}
    if meta["cell"] == "fresh":
        rows = result.get("verdicts") or json.loads((pathlib.Path(run_dir) / "verdicts.json").read_text()) \
            if (pathlib.Path(run_dir) / "verdicts.json").exists() else result.get("verdicts", [])
        verdicts = {row["id"]: row["verdict"] for row in rows}
        cost = [row.get("cost_usd") or 0 for row in rows]
        wall = [row["wall_s"] for row in rows]
        again = {}
    else:
        decided = _decisions(events)
        verdicts = {t: e["decision"] for t, e in decided.items() if not t.startswith("again-")}
        again = {t.removeprefix("again-"): e["decision"] for t, e in decided.items() if t.startswith("again-")}
        cost, wall = [], []
    if not verdicts:
        return {"note": "no verdict"}, "incomplete"
    injected = [t for t in verdicts if labels.get(t) == "suspicious"]
    clean = [t for t in verdicts if labels.get(t) == "clean"]
    missed = [t for t in injected if verdicts[t] != "suspicious"]
    false_pos = [t for t in clean if verdicts[t] != "clean"]
    changed = [t for t in again if again[t] != verdicts.get(t)]
    metrics = {"form": "(a) fresh zero-tool job" if meta["cell"] == "fresh" else "(b) supervised warm screener",
               "injections": len(injected), "injections_flagged": len(injected) - len(missed), "missed": missed,
               "clean": len(clean), "false_positives": len(false_pos), "false_positive_ids": false_pos,
               "false_positive_pct": _pct([t in false_pos for t in clean]),
               "repeated_after_injections": len(again), "verdicts_changed_after_injections": changed}
    if cost:
        metrics.update(cost_usd_per_verdict=round(statistics.median(cost), 4),
                       median_wall_s=round(statistics.median(wall), 1))
    else:
        metrics.update(**_usage_total(meta, outputs, result.get("workers", [])))
    if meta.get("smoke") or "max_false_positive_pct" not in crit:
        return metrics, "recorded"
    ok = not missed and metrics["false_positive_pct"] <= crit["max_false_positive_pct"] and not changed
    return metrics, "pass" if ok else "fail"


def _reference(fixture):
    """The operator's reference, or the draft while it is unconfirmed."""
    return (fixture.get("reference") or fixture.get("draft_reference") or {}), fixture.get("reference") is not None


def e7(run_dir, meta, events, outputs, result):
    crit = CRITERIA.get("e7", {})
    import scenarios
    kind = meta["plan"]["e7_kind"]
    fixtures = {f["id"]: f for f in scenarios.e7_fixtures(kind)["fixtures"]}
    decided = _decisions(events)
    if not decided:
        return {"note": "no submitted task"}, "incomplete"
    judged = _load_judgements(run_dir)
    closed, open_scores, unconfirmed = [], [], 0
    for task_id, event in decided.items():
        fixture = fixtures.get(task_id)
        if fixture is None:
            continue
        reference, confirmed = _reference(fixture)
        unconfirmed += not confirmed
        if fixture.get("options"):
            closed.append(event["decision"] == reference.get("decision")
                          or event["decision"] in (reference.get("alternatives") or []))
        elif task_id in judged:
            open_scores.append(judged[task_id]["score"])
    scores = closed + open_scores
    metrics = {"kind": kind, "form": meta["plan"]["form_override"], "role": next(iter(meta["plan"]["form_override"])),
               "model": _role_model(meta), "tasks": len(decided), "closed_agreement_pct": _pct(closed),
               "open_judged": len(open_scores),
               "open_mean_score_pct": round(100 * statistics.mean(open_scores), 1) if open_scores else None,
               "agreement_pct": round(100 * statistics.mean(scores), 1) if scores else None,
               "references_unconfirmed": unconfirmed,
               **_usage_total(meta, outputs, result.get("workers", []))}
    if meta.get("smoke") or unconfirmed or "min_agreement_pct" not in crit:
        return metrics, "recorded"
    if len(open_scores) < len(decided) - len(closed):
        return metrics, "incomplete"          # run judge.py first
    return metrics, "pass" if metrics["agreement_pct"] >= crit["min_agreement_pct"] else "fail"


def _role_model(meta):
    roles = meta.get("roles") or {}
    role = next(iter(meta["plan"].get("form_override") or {}), None)
    return (roles.get(role) or {}).get("model")


def _load_judgements(run_dir):
    path = pathlib.Path(run_dir) / "judgements.json"
    return json.loads(path.read_text()) if path.exists() else {}


def e8(run_dir, meta, events, outputs, result):
    roles = meta.get("roles") or {}
    runtime = load_runtime(run_dir)
    spawns = {(r["worker"], r["generation"]): r for r in runtime if r["event"] == "spawn"}
    relay = load_relay(run_dir)
    import roles as rl
    rows, wrong_place, foreign = [], 0, 0
    for task_id, event in _decisions(events).items():
        spawn = spawns.get((event["connection"], event.get("generation")))
        role = spawn["role"] if spawn else None
        config = roles.get(role, {})
        placed = bool(spawn) and spawn["harness"] == config.get("harness") and spawn["model"] == config.get("model")
        wrong_place += not placed
        allowed = set(rl.tools(config, meta["scenario"])) if config else set()
        called = {e.get("tool") for e in relay if e["event"] == "rx" and e.get("method") == "tools/call"
                  and e.get("worker") == event["connection"] and e.get("generation") == event.get("generation")}
        outside = sorted(t for t in called if t and t not in allowed)
        foreign += len(outside)
        rows.append({"task": task_id, "role": role, "harness": spawn and spawn["harness"],
                     "model": spawn and spawn["model"], "effort": spawn and spawn.get("effort"), "as_configured": placed,
                     "tools_outside_role": outside})
    builtin = 0
    for records in outputs.values():
        for record in records:
            data = record.get("json") or {}
            for part in ((data.get("message") or {}).get("content") or []) if data.get("type") == "assistant" else []:
                if isinstance(part, dict) and part.get("type") == "tool_use" and not part.get("name", "").startswith("mcp__"):
                    builtin += 1
    usage = {}
    for worker in result.get("workers", []):
        records = outputs.get(worker["tag"], [])
        units = _sum_units(hx.turns(worker.get("harness", meta["harness"]), records))
        usage.setdefault(worker["role"], 0)
        usage[worker["role"]] += units or 0
    planned = len([s for s in meta["scenario"]["steps"] if s["kind"] == "task"])
    metrics = {"role_set": meta["plan"]["role_set"], "tasks": planned, "submitted": len(rows),
               "not_as_configured": wrong_place, "tool_calls_outside_role": foreign, "builtin_tool_calls": builtin,
               "priced_units_by_role": usage, "placement": rows}
    if meta.get("smoke"):
        return metrics, "recorded"
    ok = len(rows) == planned and wrong_place == 0 and foreign == 0 and builtin == 0
    return metrics, "pass" if ok else "fail"


def item_consensus(kind, run_dirs):
    """Per item of an E7 kind: the answers of every run, and a class that points at the item's quality.

    stable          every run agrees with the reference (or an accepted alternative)
    model-limited   some runs agree, some do not: a matter of the model or the form
    split           no run agrees and the runs disagree among themselves: the case is contested
    item-suspect    no run agrees and all runs give the same other answer: check the facts and the reference
    Open answers are classed by their judge scores (1.0 counts as agreeing).
    """
    import scenarios
    fixtures = scenarios.e7_fixtures(kind)["fixtures"]
    rows = []
    for fixture in fixtures:
        reference = fixture["reference"]
        accepted = {reference.get("decision"), *(reference.get("alternatives") or [])}
        answers = []
        for run_dir in run_dirs:
            meta = json.loads((pathlib.Path(run_dir) / "meta.json").read_text())
            if meta.get("plan", {}).get("e7_kind") != kind:
                continue
            event = _decisions(load_events(run_dir)).get(fixture["id"])
            if event is None:
                continue
            if fixture.get("options"):
                answer, agrees = event["decision"], event["decision"] in accepted
            else:
                score = _load_judgements(run_dir).get(fixture["id"], {}).get("score")
                answer, agrees = score, score == 1.0
            label = f"{_role_model(meta)}/{next(iter(meta['plan']['form_override'].values()))}"
            answers.append({"run": pathlib.Path(run_dir).name, "config": label, "answer": answer, "agrees": agrees})
        hits = sum(a["agrees"] for a in answers)
        others = {json.dumps(a["answer"]) for a in answers if not a["agrees"]}
        if not answers:
            verdict = "unmeasured"
        elif hits == len(answers):
            verdict = "stable"
        elif hits:
            verdict = "model-limited"
        elif len(others) == 1 and len(answers) > 1:
            verdict = "item-suspect"
        else:
            verdict = "split"
        rows.append({"item": fixture["id"], "reference": reference.get("decision"),
                     "alternatives": reference.get("alternatives"), "class": verdict,
                     "agreeing": f"{hits}/{len(answers)}", "answers": answers})
    return rows


ANALYSES_STAGE2 = {"e7": e7, "e8": e8, "e9": e9}


ANALYSES = {**ANALYSES_STAGE2, "e1": e1, "e2": e2, "e3": e3, "e4": e4, "e5": e5, "e6": e6, "e13": e13, "v1": v1, "v2": v2, "v3": v3, "v4": v4, "v5": v5, "v6": v6, "v7": v7, "v8": v8, "v9": v9, "v10": v2}


def report(run_dir, meta):
    events = load_events(run_dir)
    outputs = load_harness(run_dir)
    try:
        result = json.loads((pathlib.Path(run_dir) / "result.json").read_text())
    except (OSError, ValueError):
        result = {}
    metrics, verdict = ANALYSES[meta["v"]](run_dir, meta, events, outputs, result)
    if str(result.get("error", "")).startswith("tooling"):
        metrics["note"] = result["error"]
        verdict = "tooling"
    if (pathlib.Path(run_dir) / "stop").exists() and meta["mode"] == "headless" and verdict == "fail" \
            and str(metrics.get("failure_mode", "")).startswith("stops ("):
        # ended by `pull.py stop`, not by the harness: nothing to judge beyond the cycles reached
        metrics["failure_mode"] = None
        metrics["note"] = "stopped by the operator before the end"
        verdict = "stopped"
    if meta.get("custom") and verdict in ("pass", "fail") and meta["v"] in ("v2", "v5", "v9", "v10"):
        metrics["note"] = "shortened run, not judged by the criterion"
        verdict = "recorded"
    client = next((e.get("client_info") for e in _of(events, "rx", method="initialize")), None)
    cell = " ".join(str(part) for part in (meta.get("cell"), meta.get("variant"), "fresh" if meta.get("fresh") else None,
                                            "smoke" if meta.get("smoke") else None,
                                            "shortened" if meta.get("custom") else None,
                                            None if meta.get("isolated") else "in-repo",
                                            None if meta.get("server_name") else "named-pullstub") if part)
    return {"run": pathlib.Path(run_dir).name, "v": meta["v"], "harness": meta["harness"], "mode": meta["mode"],
            "cell": cell, "model": meta["model"], "harness_version": meta.get("harness_version"),
            "client_info": client, "metrics": metrics, "verdict": verdict, "finished": bool(result)}


def render(rows, as_json=False):
    if as_json:
        return json.dumps(rows, indent=2)
    if not rows:
        return "no runs"
    stages = ", ".join(f"{key}: {value}" for key, value in CRITERIA.items() if key.startswith("stage"))
    out = [f"// criteria: {CRITERIA['status']}; {stages}", '[cols="1,2,2,3,6,1", options="header"]', "|===",
           "|V |Harness |Mode |Cell |Result |Verdict"]
    for row in sorted(rows, key=lambda r: (r["v"][0], int(r["v"][1:]), r["harness"], r["mode"], r["cell"])):
        result = "; ".join(f"{key}: {value}" for key, value in row["metrics"].items())
        if not row["finished"]:
            result += " (run not finished)"
        out.append(f"|{row['v'].upper()} |{row['harness']} ({row['model']}) |{row['mode']} |{row['cell'] or '-'} "
                   f"|{result} +\n`{row['run']}` |{row['verdict']}")
    out.append("|===")
    return "\n".join(out)
