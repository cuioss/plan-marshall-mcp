#!/usr/bin/env python3
"""Driver of the pull-mechanism verifications V1 to V10 (roadmap Milestone 0, Part A) and of the evaluation
E1 to E13 (doc/concepts/harness-as-worker/evaluation.adoc).

Usage:
  pull.py setup                                   check the runner jar and the three harnesses
  pull.py consent-agy                             show and accept the change to the Antigravity user config
  pull.py selfcheck [--seconds N] [--silent]      prove the stub holds one call for N seconds (default 3600),
                                                  with progress frames or, with --silent, without any
  pull.py run <v1..v10|e1..e13> <claude|opencode|agy> <headless|interactive>
              [--cell NAME|all] [--model M] [--smoke] [--cycles N] [--wait S] [--reps N] [--rounds N]
              [--variant ID|all] [--fresh] [--transport stdio|http]
  pull.py status [RUN]                            progress of one run, or the list of runs
  pull.py stop <RUN>                              stop the server and the harness of a run
  pull.py report [v1..v10|e1..e13] [--run RUN]    metrics and verdicts as AsciiDoc table rows
  pull.py cleanup                                 stop every run, remove the Antigravity server entry

Run data: .plan/temp/pull-spike/runs/<run>/ (scenario.json, events.jsonl, harness-*.jsonl, result.json; for an
evaluation item also supervisor.jsonl, the record of the job runtime in supervisor.py).
A run is driven by a detached supervisor process, so it survives the calling shell.
"""
import argparse
import json
import os
import pathlib
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import analyze  # noqa: E402
import harness as hx  # noqa: E402
import mcpclient  # noqa: E402
import scenarios  # noqa: E402
import supervisor as rt  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[4]
BASE = ROOT / ".plan" / "temp" / "pull-spike"
RUNS = BASE / "runs"
JAR = ROOT / "plan-marshall-mcp" / "target" / "quarkus-app" / "quarkus-run.jar"
BUILD = ('python3 .plan/execute-script.py plan-marshall:build-maven:maven run '
         '--command-args "package -pl plan-marshall-mcp -am -DskipTests"')
AGY_CONSENT = BASE / "agy-consent"
AGY_LOCK = BASE / "agy.lock"


def now_ms():
    return int(time.time() * 1000)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except (OSError, TypeError):
        return False


def read_json(path, default=None):
    try:
        return json.loads(pathlib.Path(path).read_text())
    except (OSError, ValueError):
        return default


def write_json(path, data):
    pathlib.Path(path).write_text(json.dumps(data, indent=2))


# --- server -------------------------------------------------------------------------------------------

def start_server(run_dir, scenario):
    """Starts the stub for one run; returns (process, url)."""
    if not JAR.exists():
        sys.exit(f"runner jar missing: {JAR}\nbuild it with: {BUILD}")
    write_json(run_dir / "scenario.json", scenario)
    # every run uses a snapshot of the build, so a rebuild never pulls the jar from under a running stub
    snapshot = BASE / "app" / str(int(JAR.stat().st_mtime))
    if not snapshot.exists():
        shutil.copytree(JAR.parent, snapshot)
    port, management = free_port(), free_port()
    argv = ["java", f"-Dpm.spike.scenario={run_dir / 'scenario.json'}", f"-Dpm.spike.run-dir={run_dir}",
            f"-Dquarkus.http.port={port}", f"-Dquarkus.management.port={management}",
            "-Dquarkus.http.host=127.0.0.1",
            # the server must never end a held call itself: both limits default to 30 minutes
            "-Dquarkus.http.idle-timeout=6H", "-Dquarkus.mcp.server.connection-idle-timeout=6H",
            "-jar", str(snapshot / JAR.name)]
    log = open(run_dir / "server.log", "ab")
    process = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT, start_new_session=True)
    deadline = time.time() + 60
    while time.time() < deadline:
        if process.poll() is not None:
            sys.exit(f"stub exited with {process.returncode}, see {run_dir / 'server.log'}")
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
            return process, f"http://127.0.0.1:{port}/mcp"
        except OSError:
            time.sleep(0.3)
    process.kill()
    sys.exit("stub did not open its port within 60 s")


kill_group = rt.kill_group


# --- supervisor ---------------------------------------------------------------------------------------

class Worker:
    """One harness process whose output lines are stored with their arrival time."""

    def __init__(self, run_dir, tag, argv, env, cwd):
        self.tag = tag
        self.started_ms = now_ms()
        self.out = open(run_dir / f"harness-{tag}.jsonl", "a")
        self.lock = threading.Lock()
        self.process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, env=env, cwd=cwd, start_new_session=True)
        self.threads = [threading.Thread(target=self._pump, args=(stream, name), daemon=True)
                        for stream, name in ((self.process.stdout, "out"), (self.process.stderr, "err"))]
        for thread in self.threads:
            thread.start()

    def _pump(self, stream, name):
        for raw in stream:
            line = raw.decode("utf-8", "replace").rstrip("\n")
            with self.lock:
                self.out.write(json.dumps({"t_ms": now_ms(), "s": name, "line": line}) + "\n")
                self.out.flush()

    def wait(self, timeout):
        try:
            code = self.process.wait(timeout)
        except subprocess.TimeoutExpired:
            return None
        for thread in self.threads:
            thread.join(5)
        return code

    def summary(self, **extra):
        return {"tag": self.tag, "pid": self.process.pid, "started_ms": self.started_ms,
                "exit_code": self.process.returncode, **extra}


class Supervisor:
    def __init__(self, run_dir):
        self.run_dir = pathlib.Path(run_dir)
        self.meta = read_json(self.run_dir / "meta.json")
        self.plan = self.meta["plan"]
        # The workspace lies outside the repository: a harness started inside it loads the project's
        # instructions (CLAUDE.md) and shares the project's memory across sessions, which leaked a value
        # from one run into later ones.
        self.ws = pathlib.Path(tempfile.gettempdir()) / "pull-spike-ws" / self.run_dir.name
        self.url = None
        self.result = {"workers": []}
        self.count = 0

    def events(self):
        return analyze.load_events(self.run_dir)

    def wait_event(self, predicate, timeout, after=0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for index, event in enumerate(self.events()):
                if index >= after and predicate(event):
                    return index, event
            if (self.run_dir / "stop").exists():
                return None, None
            time.sleep(0.5)
        return None, None

    def launch(self, prompt, resume=None, extra_tools=()):
        """Starts one headless harness process; its worker id is its tag, `w001`, `w002`, ..."""
        self.count += 1
        tag = f"w{self.count:03d}"
        transport = self.meta["transport"]
        # agy has one global server entry, so its relay cannot carry a worker id per process
        worker = hx.AGY_WORKER if self.meta["harness"] == "agy" and transport != "http" else tag
        prompt = scenarios.prompt(prompt, worker, transport)
        argv, env = hx.headless(self.meta["harness"], self.ws, self.run_dir, self.url, prompt,
                                self.meta["model"], worker,
                                transport=transport, raised=self.plan.get("raised", False), resume=resume,
                                extra_tools=extra_tools)
        if self.count == 1:
            self.meta["command"] = [part if part != prompt else "<prompt>" for part in argv]
            self.meta["prompt"] = prompt
            write_json(self.run_dir / "meta.json", self.meta)
        return Worker(self.run_dir, tag, argv, env, self.ws)

    def stopped(self):
        return (self.run_dir / "stop").exists()

    def wait_worker(self, worker, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline and not self.stopped():
            code = worker.wait(2)
            if code is not None:
                return code
        kill_group(worker.process.pid, signal.SIGKILL)
        worker.wait(10)
        return None

    # plans ------------------------------------------------------------------------------------------

    def loop(self):
        worker = self.launch(scenarios.LOOP_PROMPT)
        code = self.wait_worker(worker, self.plan["deadline_s"])
        self.result["workers"].append(worker.summary(ended_ms=now_ms(), timed_out=code is None))

    def interactive(self):
        deadline = time.time() + self.plan["deadline_s"]
        while time.time() < deadline and not self.stopped():
            time.sleep(2)

    def fresh(self):
        prompt = scenarios.ONE_TASK_PROMPT
        if self.plan.get("representative"):
            filler = (ROOT / "doc" / "specification" / "hypermedia-format" /
                      "01-representation-and-link-forms.adoc").read_text()[:30000]
            prompt += "\n\nReference material (not needed for the decision):\n" + filler
        for _ in range(self.plan["count"]):
            if self.stopped():
                break
            time.sleep(self.plan.get("gap_s", 0))
            worker = self.launch(prompt)
            code = self.wait_worker(worker, 600)
            self.result["workers"].append(worker.summary(ended_ms=now_ms(), timed_out=code is None))

    def sigterm(self):
        worker = self.launch(scenarios.LOOP_PROMPT)
        _, event = self.wait_event(lambda e: e["event"] == self.plan["at"], 300)
        if event is None:
            self.result["error"] = f"no {self.plan['at']} event within 300 s"
            kill_group(worker.process.pid, signal.SIGKILL)
            return
        time.sleep(self.plan.get("delay_s", 0))
        sent = now_ms()
        os.kill(worker.process.pid, signal.SIGTERM)
        code = worker.wait(60)
        exited = now_ms()
        time.sleep(2)
        orphans = subprocess.run(["pgrep", "-g", str(worker.process.pid)], capture_output=True, text=True).stdout.split()
        kill_group(worker.process.pid, signal.SIGKILL)
        self.result["workers"].append(worker.summary(
            sigterm_ms=sent, exit_after_ms=None if code is None else exited - sent, orphans=len(orphans)))

    def resume(self):
        first = self.launch(scenarios.LOOP_PROMPT)
        self.wait_worker(first, 300)
        records = analyze.load_harness_file(self.run_dir / f"harness-{first.tag}.jsonl")
        session = hx.session_id(records)
        self.result["workers"].append(first.summary(session_id=session))
        if not session:
            self.result["error"] = "no session id in the harness output"
            return
        started = now_ms()
        second = self.launch(scenarios.RESUME_PROMPT, resume=session)
        self.wait_worker(second, 300)
        text = (self.run_dir / f"harness-{second.tag}.jsonl").read_text()
        self.result["workers"].append(second.summary(resume_ms=now_ms() - started,
                                                     nonce_recalled=self.plan["nonce"] in text))

    def kill_trials(self):
        live, trials, seen = {}, [], 0

        def start():
            worker = self.launch(scenarios.LOOP_PROMPT)
            live[worker.tag] = worker
            return worker

        workers = [start(), start()]
        for _ in range(self.plan["trials"]):
            index, event = self.wait_event(
                lambda e: e["event"] == "wait_end" and e.get("outcome") == "task" and e["connection"] in live,
                600, seen)
            if event is None:
                break
            seen = index + 1
            victim = live.pop(event["connection"])
            kill_group(victim.process.pid, signal.SIGKILL)
            victim.wait(10)
            delivered = [e for e in self.events()[:index + 1]
                         if e["event"] == "delivery" and e["connection"] == event["connection"]]
            workers.append(start())
            if delivered:
                task_id = delivered[-1]["task_id"]
                trials.append({"task_id": task_id, "connection": event["connection"], "killed_ms": now_ms()})
                self.wait_event(lambda e: e["event"] == "submit" and e.get("task_id") == task_id, 300, seen)
            # Deliveries that happened while waiting are skipped on purpose: a kill must fall between a
            # delivery and its submit, and those tasks may already be submitted.
            seen = len(self.events())
        self.result["trials"] = trials
        deadline = time.time() + 900
        while time.time() < deadline and not self.stopped() and any(w.process.poll() is None for w in live.values()):
            time.sleep(2)
        for worker in workers:
            kill_group(worker.process.pid, signal.SIGKILL)
            self.result["workers"].append(worker.summary())

    # plans of the evaluation: the supervised task protocol -------------------------------------------

    PROMPTS = {
        "protocol": lambda self: scenarios.protocol_prompt(),
        "consultant": lambda self: scenarios.protocol_prompt(scenarios.SKILL_CONSULTANT),
        "turn_end": lambda self: scenarios.protocol_prompt(closing=scenarios.TURN_END_CLOSING),
        "one_task": lambda self: scenarios.protocol_prompt(self.plan["skill"], one_task=True),
    }

    def spawn(self, slot, prompt_name):
        """Starts the next generation of a slot; the tag of its output file names worker and generation."""
        prompt = self.PROMPTS[prompt_name](self)
        scenario = self.meta["scenario"]
        argv, env = hx.headless(slot.harness, self.ws, self.run_dir, self.url, prompt, slot.model, slot.worker,
                                generation=slot.generation, role=slot.role,
                                tools=scenarios.worker_tools(scenario, slot.role))
        if "command" not in self.meta:
            self.meta["command"] = [part if part != prompt else "<prompt>" for part in argv]
            self.meta["prompt"] = prompt
            write_json(self.run_dir / "meta.json", self.meta)
        return Worker(self.run_dir, f"{slot.worker}g{slot.generation}", argv, env, self.ws)

    def runtime(self):
        runtime = self.active = rt.JobRuntime(self.run_dir, self.url, self.spawn, self.plan,
                                              self.meta["scenario"]["wait_seconds"])
        for entry in self.plan["slots"]:
            harness = entry.get("harness", self.meta["harness"])
            model = self.meta["model"] if harness == self.meta["harness"] else hx.DEFAULT_MODEL[harness]
            runtime.add(entry["role"], harness, model, entry["prompt"], entry["eager"], entry.get("count", 1))
        if self.meta["scenario"].get("skills_dir"):
            self.result["shim_dirs"] = sorted({hx.install_shim(slot.harness, self.ws) for slot in runtime.slots}
                                              | {hx.install_shim(self.meta["harness"], self.ws)})
        return runtime

    def supervise(self, runtime, each=None):
        """Runs the job runtime until the stub reports the work as finished, the deadline, or a stop."""
        deadline = time.time() + self.plan["deadline_s"]
        controls = list(self.plan.get("controls", []))
        while time.time() < deadline and not self.stopped():
            events = runtime.step()
            for control in controls[:]:
                if runtime.submits >= control["after_submits"]:
                    controls.remove(control)
                    self.control(runtime, control)
            if each is not None and each(events):
                break
            if runtime.state.get("finished"):
                break
            if runtime.aborted:
                self.result["error"] = "tooling: " + runtime.aborted
                break
            if not self.plan.get("respawn", True) and runtime.slots and all(
                    slot.state == "empty" and slot.generation > 0 for slot in runtime.slots):
                self.result["note"] = "every worker ended and none is replaced (no recycling)"
                break
            time.sleep(0.2)
        self.result["state_at_end"] = runtime.state
        self.result["workers"] = runtime.close()

    def control(self, runtime, control):
        """A scheduled driver action of E13: a synthetic compaction, or a new digest of a skill."""
        if control["do"] == "compacted":
            for slot in runtime.slots:
                runtime.control.call("spike_compacted", worker=slot.worker)
        elif control["do"] == "skill_update":
            content = scenarios.skill(control["uri"]) + "\nRevision 2 of this skill; rule D1 is unchanged.\n"
            runtime.control.call("spike_skill_update", uri=control["uri"], content=content)
        runtime.log("control", **control)

    def supervised(self):
        self.supervise(self.runtime())

    consult = supervised

    def faults(self):
        """E2 and E6: one injected fault per trial against a running pool."""
        runtime, fault, trials = self.runtime(), self.plan["fault"], []
        state = {"armed_ms": now_ms() + 1000, "trial": None, "deadline": 0}

        def each(events):
            trial = state["trial"]
            if trial is None:
                if not all(slot.live() for slot in runtime.slots):
                    return False
                trial = self.inject(runtime, fault, events, state["armed_ms"])
                if trial:
                    state.update(trial=trial, deadline=time.time() + self.plan.get("stop_s", 0) + 300)
                    runtime.log("inject", **trial)
                return False
            if self.settled(runtime, trial, events) or time.time() > state["deadline"]:
                trial["settled_ms"] = now_ms()
                trials.append(trial)
                self.result["trials"] = trials
                write_json(self.run_dir / "trials.json", trials)
                state.update(trial=None, armed_ms=now_ms() + 3000)
            return len(trials) >= self.plan["trials"] and state["trial"] is None

        self.supervise(runtime, each)
        self.result["trials"] = trials

    agy_concurrent = faults

    def inject(self, runtime, fault, events, armed_ms):
        """Injects the fault when its trigger is among the new events; returns the trial or None."""
        if fault == "turn-end":
            slot = next((s for s in runtime.slots if s.live() and not runtime.holds(s)), None)
            if slot is None:
                return None
            slot.hold = True
            runtime.end(slot, "trial_setup")
            runtime.wait_empty(slot)
            runtime.start(slot, "turn_end")
            slot.hold = False
            return {"fault": fault, "worker": slot.worker, "generation": slot.generation, "injected_ms": now_ms()}
        trigger = {"kill-offer": "offer", "kill-exec": "ack", "stop-short": "ack", "stop-long": "ack",
                   "relay-kill": "wait_start"}[fault]
        for event in events:
            slot = runtime.slot(event.get("connection"), event.get("generation"))
            if event["event"] != trigger or event["t_ms"] < armed_ms or slot is None or not slot.live():
                continue
            if fault == "relay-kill" and event.get("status") != "held":
                continue
            pid = slot.proc.process.pid
            trial = {"fault": fault, "worker": slot.worker, "generation": slot.generation,
                     "task_id": event.get("task_id"), "trigger_ms": event["t_ms"]}
            if fault in ("kill-offer", "kill-exec"):
                kill_group(pid, signal.SIGKILL)
            elif fault == "relay-kill":
                time.sleep(2)
                trial["relay_pids"] = runtime.relay_pids(slot)
                for relay in trial["relay_pids"]:
                    os.kill(relay, signal.SIGKILL)
            else:
                slot.no_kill = fault == "stop-long"
                kill_group(pid, signal.SIGSTOP)
                trial["stop_s"] = self.plan["stop_s"]
                threading.Timer(self.plan["stop_s"], kill_group, (pid, signal.SIGCONT)).start()
            trial["injected_ms"] = now_ms()
            return trial
        return None

    def settled(self, runtime, trial, events):
        """A trial is settled when its task is submitted, or, without a task, when a successor waits."""
        for event in events:
            if event["event"] == "ack" and trial["fault"] == "turn-end" and "task_id" not in trial \
                    and event.get("connection") == trial["worker"] and event.get("generation") == trial["generation"]:
                trial["task_id"] = event["task_id"]
            if event["event"] == "submit" and event.get("task_id") == trial.get("task_id"):
                trial["submitted_ms"] = event["t_ms"]
                trial["submitted_by"] = [event["connection"], event.get("generation")]
            if trial["fault"] == "relay-kill" and event["event"] == "wait_start" \
                    and event.get("connection") == trial["worker"] and event["t_ms"] > trial["injected_ms"]:
                # a host may restart its stdio server itself; the worker then goes on in its generation
                recovered = event.get("generation", 0) == trial["generation"]
                trial["recovered_ms" if recovered else "successor_waits_ms"] = event["t_ms"]
        if trial["fault"] == "stop-long" and "submitted_ms" in trial:
            # the stalled worker resumes into the fence: give it time to be refused
            return now_ms() >= trial["injected_ms"] + (trial["stop_s"] + 20) * 1000
        return any(key in trial for key in ("submitted_ms", "successor_waits_ms", "recovered_ms"))

    def fresh_skills(self):
        """E13: one fresh job per task, the role skill in-band in its prompt."""
        runtime = self.runtime()
        runtime.add("worker", self.meta["harness"], self.meta["model"], "one_task", False)
        slot = runtime.slots[0]
        slot.hold = True
        for _ in range(self.plan["count"]):
            if self.stopped():
                break
            runtime.start(slot)
            deadline = time.time() + 600
            while time.time() < deadline and slot.state != "empty" and not self.stopped():
                runtime.step()
                time.sleep(0.2)
        self.result["state_at_end"] = runtime.control.state()
        self.result["workers"] = runtime.close()

    def uri_switch(self):
        """E13: a client that declares the Skills Extension is named its skills by URI; another gets them in-band."""
        seen = {}
        for name, capabilities in (("declaring", {"extensions": {rt_extension(): {}}}), ("plain", {})):
            client = mcpclient.McpClient(self.url, timeout=60)
            client.initialize(name=f"pull-{name}", capabilities=capabilities)
            answer, _ = client.call("pull_wait", {"worker": name, "generation": 1, "role": "worker"})
            offer = json.loads(answer["result"]["content"][0]["text"])
            seen[name] = {"task_id": offer.get("task_id"),
                          "skills": [{"uri": s.get("uri"), "digest": s.get("digest"), "inband": "content" in s}
                                     for s in offer.get("skills", [])]}
            read, _ = client.request("resources/read", {"uri": scenarios.SKILL_PROTOCOL})
            seen[name]["resources_read_ok"] = bool((read.get("result") or {}).get("contents"))
        # a host speaks to the relay one request after the other, as over any stdio server
        relay = subprocess.Popen(hx.relay_argv(self.url, "relay-check", self.run_dir, 1, "worker"),
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        answers = {}

        def ask(message):
            relay.stdin.write(json.dumps({"jsonrpc": "2.0", **message}) + "\n")
            relay.stdin.flush()
            while "id" in message:
                line = relay.stdout.readline()
                if not line:
                    return
                answer = json.loads(line)
                if answer.get("id") == message["id"]:
                    answers[message["id"]] = answer
                    return

        ask({"id": 1, "method": "initialize", "params": {
            "protocolVersion": mcpclient.PROTOCOL, "capabilities": {},
            "clientInfo": {"name": "pull-relay-check", "version": "0"}}})
        ask({"method": "notifications/initialized"})
        ask({"id": 2, "method": "skills/list"})
        ask({"id": 3, "method": "skills/get", "params": {"uri": scenarios.SKILL_PROTOCOL}})
        ask({"id": 4, "method": "tools/list"})
        ask({"id": 5, "method": "tools/call", "params": {"name": "spike_state"}})
        relay.stdin.close()
        relay.wait(10)
        tools = [tool["name"] for tool in (answers.get(4, {}).get("result") or {}).get("tools", [])]
        seen["relay"] = {
            "skills_list": len((answers.get(2, {}).get("result") or {}).get("skills", [])),
            "skills_get_ok": bool((answers.get(3, {}).get("result") or {}).get("contents")),
            "spike_tools_listed": len([tool for tool in tools if tool.startswith("spike_")]),
            "spike_call_refused": "error" in answers.get(5, {})}
        self.result["uri_switch"] = seen

    def session(self):
        """Writes the workspace of the interactive session of E4 and E13 and returns its launch command."""
        session = self.plan["session"]
        harness, scenario = self.meta["harness"], self.meta["scenario"]
        hx.install_shim(harness, self.ws)
        return hx.interactive(harness, self.ws, self.run_dir, self.url, False, scenarios.SESSION_POINTER,
                              scenarios.TUI_WORKER, identity=(1, session["role"], session.get("declare_skills", False)),
                              tools=scenarios.worker_tools(scenario, session["role"]))

    def session_first(self):
        self.meta["launch"] = self.session()
        write_json(self.run_dir / "meta.json", self.meta)
        self.supervise(self.runtime())

    skills_interactive = session_first

    def run(self):
        self.ws.mkdir(parents=True, exist_ok=True)
        server, self.url = start_server(self.run_dir, self.meta["scenario"])
        self.meta.update(url=self.url, server_pid=server.pid, supervisor_pid=os.getpid(), started_ms=now_ms())
        supervised = bool(self.meta["scenario"].get("supervised"))
        harnesses = {self.meta["harness"]} | {slot.get("harness") for slot in self.plan.get("slots", [])}
        agy = "agy" in harnesses
        try:
            if agy:
                AGY_LOCK.write_text(str(os.getpid()))
                self.meta["agy_register"] = hx.agy_register(self.url, self.run_dir, self.meta["transport"],
                                                            env_identity=supervised)
            if supervised:
                write_json(self.run_dir / "meta.json", self.meta)
                getattr(self, self.plan["kind"])()
            elif self.meta["mode"] == "interactive":
                self.meta["launch"] = hx.interactive(
                    self.meta["harness"], self.ws, self.run_dir, self.url, self.plan.get("raised", False),
                    scenarios.POINTER,
                    hx.AGY_WORKER if agy else scenarios.TUI_WORKER, self.meta["transport"])
                write_json(self.run_dir / "meta.json", self.meta)
                self.interactive()
            else:
                write_json(self.run_dir / "meta.json", self.meta)
                getattr(self, self.plan["kind"])()
        finally:
            if agy:
                self.result["agy_unregister"] = hx.agy_unregister()
                AGY_LOCK.unlink(missing_ok=True)
            for entry in getattr(self, "active", None).processes if hasattr(self, "active") else []:
                if "ended_ms" not in entry:        # the plan broke off: no worker may outlive its stub
                    kill_group(entry["pid"], signal.SIGKILL)
            kill_group(server.pid, signal.SIGTERM)
            self.result["ended_ms"] = now_ms()
            write_json(self.run_dir / "result.json", self.result)


# --- verbs --------------------------------------------------------------------------------------------

def rt_extension():
    return "io.modelcontextprotocol/skills"


def cmd_setup(_args):
    BASE.mkdir(parents=True, exist_ok=True)
    report = {"jar": str(JAR), "jar_present": JAR.exists(),
              "java": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0],
              "harnesses": {h: hx.version(h) for h in hx.HARNESSES},
              "models": hx.DEFAULT_MODEL, "raised_timeout_knobs": hx.RAISED,
              "agy_consent": AGY_CONSENT.exists()}
    write_json(BASE / "setup.json", report)
    print(json.dumps(report, indent=2))
    if not JAR.exists():
        print(f"\nBuild the runner jar first:\n  {BUILD}")
        return 1
    return 0


def cmd_consent_agy(args):
    print("Antigravity has no per-call MCP configuration. Every agy run will execute\n"
          f"  agy mcp add {hx.SERVER} http://127.0.0.1:<port>/mcp\n"
          "which writes the server entry into your Antigravity user configuration (~/.gemini/config),\n"
          f"and `agy mcp remove {hx.SERVER}` when the run ends or on `pull.py cleanup`.\n"
          "agy runs are therefore sequential.")
    if not args.yes:
        print("\nRe-run with --yes to accept.")
        return 1
    BASE.mkdir(parents=True, exist_ok=True)
    AGY_CONSENT.write_text(time.strftime("%Y-%m-%dT%H:%M:%S"))
    print("\nAccepted.")
    return 0


def cmd_selfcheck(args):
    run_dir = RUNS / f"selfcheck-{'silent' if args.silent else 'progress'}-{time.strftime('%m%d-%H%M%S')}"
    run_dir.mkdir(parents=True)
    scenario = {"wait_seconds": args.seconds, "progress_seconds": 0 if args.silent else 5,
                "steps": [scenarios.wait(1), scenarios.DONE]}
    server, url = start_server(run_dir, scenario)
    try:
        client = mcpclient.McpClient(url, timeout=args.seconds + 120)
        client.initialize()
        started = time.time()
        result, notifications = client.call("pull_wait", progress_token=None if args.silent else "selfcheck")
        held = time.time() - started
        text = result.get("result", {}).get("content", [{}])[0].get("text", "")
        ok = "wait_again" in text and held >= args.seconds
        print(json.dumps({"run": run_dir.name, "held_s": round(held, 1), "requested_s": args.seconds,
                          "progress_frames": len(notifications), "answer": text, "ok": ok}, indent=2))
        return 0 if ok else 1
    finally:
        kill_group(server.pid)


def cmd_run(args):
    stage = scenarios.STAGE.get(args.v, "status")
    if not args.smoke and analyze.CRITERIA.get(stage) != "confirmed":
        sys.exit("criteria.json has %s %r: a measured run needs the operator's confirmed criteria "
                 "(set \"%s\": \"confirmed\" and commit). Use --smoke for a trial run."
                 % (stage, analyze.CRITERIA.get(stage), stage))
    cross = args.v == "e5" and args.cell == "cross" and scenarios.NEXT_HARNESS[args.harness] == "agy"
    if args.harness == "agy" or cross:
        if not AGY_CONSENT.exists():
            sys.exit("Antigravity runs change your user configuration; run `pull.py consent-agy` first.")
        owner = read_json(AGY_LOCK)
        if owner and alive(owner):
            sys.exit("another agy run is active; agy runs are sequential (one server entry in the user config)")
    cells = scenarios.CELLS.get(args.v, [None])
    if args.cell and args.cell != "all":
        if args.cell not in cells:
            sys.exit(f"unknown cell {args.cell!r} for {args.v}; choose from {cells} or all")
        cells = [args.cell]
    elif args.cell != "all" and cells != [None]:
        sys.exit(f"{args.v} needs --cell: one of {cells} or all")
    variants = [None]
    if args.v == "v8":
        known = [x["id"] for x in scenarios.injections()["variants"]]
        variants = known if args.variant in (None, "all") else [args.variant]
    if args.harness == "agy" and len(cells) * len(variants) > 1:
        sys.exit("agy runs are sequential: start one cell or variant at a time")
    if args.v.startswith("e") and len(cells) > 1:
        sys.exit("an evaluation run takes both model slots: start one cell at a time")
    if args.v == "e6" and args.harness != "agy":
        sys.exit("e6 measures Antigravity as a concurrent job host: the harness is agy")
    started = []
    for cell in cells:
        for variant in variants:
            started.append(_start_run(args, cell, variant))
    for run_dir in started:
        print(f"run {run_dir.name}")
        if args.mode == "interactive":
            _print_checklist(run_dir)
    print("\nfollow with: pull.py status <run>   |   pull.py report " + args.v)
    return 0


def _start_run(args, cell, variant):
    opts = {"cell": cell or "", "smoke": args.smoke, "cycles": args.cycles, "wait": args.wait, "reps": args.reps,
            "gap": args.gap, "harness": args.harness,
            "rounds": args.rounds, "variant": variant, "fresh": args.fresh}
    scenario, plan = scenarios.build(args.v, opts)
    # a run with its own cycle count, wait, repetitions or rounds is recorded, never judged by the criterion
    custom = any(value is not None for value in (args.cycles, args.wait, args.reps, args.rounds, args.gap))
    if "session" in plan and args.mode != "interactive":
        sys.exit(f"{args.v} {cell} needs the operator: start it with the mode `interactive`")
    if args.mode == "interactive" and "session" not in plan:
        if scenario.get("supervised"):
            sys.exit(f"{args.v} {cell} runs headless")
        plan = {**plan, "kind": "interactive", "deadline_s": max(plan["deadline_s"], 3600) + 1800}
    parts = [args.v, args.harness, args.mode[0], cell, variant, "fresh" if args.fresh else None,
             "http" if args.transport == "http" else None, None if args.model is None else "alt",
             "custom" if custom else None,
             "smoke" if args.smoke else None, time.strftime("%m%d-%H%M%S")]
    run_dir = RUNS / "-".join(part for part in parts if part)
    run_dir.mkdir(parents=True)
    meta = {"v": args.v, "harness": args.harness, "mode": args.mode, "cell": cell, "variant": variant,
            "fresh": args.fresh, "smoke": args.smoke, "model": args.model or hx.DEFAULT_MODEL[args.harness],
            "transport": args.transport, "custom": custom, "isolated": True, "server_name": hx.SERVER,
            "harness_version": hx.version(args.harness), "scenario": scenario, "plan": plan,
            "created": time.strftime("%Y-%m-%dT%H:%M:%S")}
    write_json(run_dir / "meta.json", meta)
    log = open(run_dir / "supervisor.log", "ab")
    subprocess.Popen([sys.executable, __file__, "_supervise", str(run_dir)], stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.DEVNULL, start_new_session=True, cwd=ROOT)
    return run_dir


def _print_checklist(run_dir):
    deadline = time.time() + 90
    meta = read_json(run_dir / "meta.json")
    while time.time() < deadline and "launch" not in meta:
        time.sleep(1)
        meta = read_json(run_dir / "meta.json")
    if "launch" not in meta:
        print(f"  the stub did not come up, see {run_dir / 'supervisor.log'}")
        return
    lines = [f"Operator checklist for {run_dir.name}",
             f"1. In a new terminal: {meta['launch']}",
             f"2. Approve the MCP server `{hx.SERVER}` if the harness asks, and confirm it lists pull_wait.",
             "3. Paste this prompt:", "",
             scenarios.SESSION_PROMPT if meta["scenario"].get("supervised")
             else scenarios.prompt(scenarios.LOOP_PROMPT, scenarios.TUI_WORKER, meta["transport"]), ""]
    if meta["v"] == "e4":
        lines += ["4. Work with the session as usual for the hour: ask it questions about anything, and note for",
                  "   each question how long it took until the session reacted (it may be inside a pull_wait).",
                  "5. After about 20 minutes type /clear, then paste the prompt of step 3 again when you want the",
                  "   session to take tasks again. Note whether tasks were done by the standby meanwhile.",
                  "6. After about 40 minutes compact manually (/compact) and note whether the session goes on",
                  "   taking tasks by itself.",
                  "7. Leave the session untouched for five minutes at least once, and at least once keep it busy",
                  "   with a long answer while a task is due (`pull.py status` shows the deliveries).",
                  f"8. At the end of the hour run: pull.py stop {run_dir.name}",
                  "   and report: the longest time your input waited, and anything the screen showed that the",
                  "   stub cannot see."]
    elif meta["v"] == "e13":
        lines += ["4. Let the session work through the tasks (about 20 minutes). Do not help it.",
                  "5. Note whether it read the named skills through pm_skill before its first submit, and",
                  "   whether the harness listed the skill `pm-shim` (ask it: which skills do you have?).",
                  f"6. When it answers END or stops, run: pull.py stop {run_dir.name}"]
    elif meta["v"] == "v10" and not meta["smoke"]:
        lines += ["4. Near cycle 60 (`pull.py status`): type /clear. Note whether the loop resumes by itself;",
                  "   if not, type the single word `continue` and note whether the first call is pull_wait.",
                  "5. Near cycle 120: compact manually (/compact) and note the same two things.",
                  "6. From cycle 200 the answers carry filler that forces an automatic compaction;",
                  "   do not type anything, and note whether the loop survives it.",
                  "7. When the model answers DONE or the loop stops, note what the screen shows, then",
                  f"   run: pull.py stop {run_dir.name}"]
    else:
        lines += [f"4. Leave the session alone. When it answers DONE or the call is aborted, note what the",
                  f"   screen shows, then run: pull.py stop {run_dir.name}"]
    text = "\n".join(lines)
    (run_dir / "checklist.txt").write_text(text + "\n")
    print(text)


def cmd_supervise(args):
    # a terminated supervisor must still run its cleanup (stub, Antigravity server entry)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    Supervisor(args.run_dir).run()
    return 0


def _resolve(name):
    run_dir = RUNS / name
    if not run_dir.is_dir():
        sys.exit(f"no such run: {name}")
    return run_dir


def cmd_status(args):
    if not args.run:
        for run_dir in sorted(RUNS.glob("*")) if RUNS.is_dir() else []:
            meta = read_json(run_dir / "meta.json", {})
            state = "done" if (run_dir / "result.json").exists() else (
                "running" if alive(meta.get("supervisor_pid")) else "dead")
            print(f"{state:8} {run_dir.name}")
        return 0
    run_dir = _resolve(args.run)
    meta = read_json(run_dir / "meta.json", {})
    events = analyze.load_events(run_dir)
    summary = analyze.progress(events)
    summary.update(run=run_dir.name, finished=(run_dir / "result.json").exists(),
                   supervisor_alive=alive(meta.get("supervisor_pid")), server_alive=alive(meta.get("server_pid")))
    print(json.dumps(summary, indent=2))
    return 0


def cmd_stop(args):
    run_dir = _resolve(args.run)
    (run_dir / "stop").write_text("")
    meta = read_json(run_dir / "meta.json", {})
    deadline = time.time() + 20
    while time.time() < deadline and alive(meta.get("supervisor_pid")):
        time.sleep(1)
    forced = alive(meta.get("supervisor_pid"))
    kill_group(meta.get("supervisor_pid"))
    kill_group(meta.get("server_pid"))
    if forced and meta.get("harness") == "agy":
        time.sleep(3)
        if alive(meta.get("supervisor_pid")):      # its own cleanup did not run
            kill_group(meta.get("supervisor_pid"), signal.SIGKILL)
        print("agy mcp remove:", hx.agy_unregister())
        AGY_LOCK.unlink(missing_ok=True)
    print(f"stopped {run_dir.name}")
    return 0


def cmd_report(args):
    runs = [_resolve(args.run)] if args.run else sorted(RUNS.glob("[ve]*")) if RUNS.is_dir() else []
    rows = []
    for run_dir in runs:
        meta = read_json(run_dir / "meta.json")
        if not meta or (args.v and meta["v"] != args.v) or (meta.get("smoke") and not args.smoke and not args.run):
            continue
        rows.append(analyze.report(run_dir, meta))
    print(analyze.render(rows, args.json))
    return 0


def cmd_cleanup(_args):
    for run_dir in sorted(RUNS.glob("*")) if RUNS.is_dir() else []:
        meta = read_json(run_dir / "meta.json", {})
        if alive(meta.get("supervisor_pid")) or alive(meta.get("server_pid")):
            (run_dir / "stop").write_text("")
            kill_group(meta.get("supervisor_pid"))
            kill_group(meta.get("server_pid"))
            print(f"stopped {run_dir.name}")
    if AGY_CONSENT.exists():
        print("agy mcp remove:", hx.agy_unregister())
        AGY_LOCK.unlink(missing_ok=True)
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="verb", required=True)
    sub.add_parser("setup").set_defaults(func=cmd_setup)
    consent = sub.add_parser("consent-agy")
    consent.add_argument("--yes", action="store_true")
    consent.set_defaults(func=cmd_consent_agy)
    selfcheck = sub.add_parser("selfcheck")
    selfcheck.add_argument("--seconds", type=int, default=scenarios.V1_CAP_SECONDS)
    selfcheck.add_argument("--silent", action="store_true", help="hold the call without progress frames")
    selfcheck.set_defaults(func=cmd_selfcheck)
    run = sub.add_parser("run")
    run.add_argument("v", choices=sorted(scenarios.BUILDERS, key=lambda v: (v[0] == "e", int(v[1:]))))
    run.add_argument("harness", choices=hx.HARNESSES)
    run.add_argument("mode", choices=["headless", "interactive"])
    run.add_argument("--cell")
    run.add_argument("--model")
    run.add_argument("--smoke", action="store_true")
    run.add_argument("--fresh", action="store_true")
    run.add_argument("--variant")
    run.add_argument("--transport", choices=["stdio", "http"], default="stdio",
                     help="stdio: through relay.py, as hosts will see pm-mcp serve (default); http: directly")
    for name in ("cycles", "wait", "reps", "rounds", "gap"):
        run.add_argument(f"--{name}", type=int)
    run.set_defaults(func=cmd_run)
    supervise = sub.add_parser("_supervise")
    supervise.add_argument("run_dir")
    supervise.set_defaults(func=cmd_supervise)
    status = sub.add_parser("status")
    status.add_argument("run", nargs="?")
    status.set_defaults(func=cmd_status)
    stop = sub.add_parser("stop")
    stop.add_argument("run")
    stop.set_defaults(func=cmd_stop)
    report = sub.add_parser("report")
    report.add_argument("v", nargs="?")
    report.add_argument("--run")
    report.add_argument("--smoke", action="store_true")
    report.add_argument("--json", action="store_true")
    report.set_defaults(func=cmd_report)
    sub.add_parser("cleanup").set_defaults(func=cmd_cleanup)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
