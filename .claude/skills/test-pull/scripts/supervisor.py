"""Job runtime of the evaluation E1 to E13: the supervision that the product puts into the server.

A pool of slots per role. Every slot runs at most one harness process, its worker, in a rising generation.
Once per round the runtime reads the stub's new events, the stub's state (`spike_state`) and the new output
of every worker, and ends a worker that is lost or due for recycling:

  exit        the harness process ended
  ack_missed  the stub released an offer the worker did not acknowledge in time
  silence     no stub event of the worker for the bounded wait plus the grace
  idle        the worker answered N consecutive waits with nothing to do
  budget      the context the harness reports reached the token budget (only between tasks)
  recycle     a fixed number of submits (E13)

Ending a worker: SIGTERM, SIGKILL after 10 s, then `spike_fence`, which releases its lease at once. A
successor of the next generation starts when the stub shows work for the role. A compaction the harness
reports is passed on with `spike_compacted`. Everything the runtime does goes to `supervisor.jsonl`.
"""
import json
import os
import pathlib
import signal
import subprocess
import threading
import time

import harness as hx
import mcpclient

TERM_GRACE_S = 10
MAX_FAILED_STARTS = 5


def now_ms():
    return int(time.time() * 1000)


def kill_group(pid, sig=signal.SIGTERM):
    try:
        os.killpg(pid, sig)
    except (OSError, TypeError):
        pass


class Control:
    """The driver's own connection to the stub, for the `spike_*` tools."""

    def __init__(self, url):
        self.url = url
        self.lock = threading.Lock()
        self.client = None

    def call(self, tool, **arguments):
        with self.lock:
            for attempt in (1, 2):
                try:
                    if self.client is None:
                        self.client = mcpclient.McpClient(self.url, timeout=30)
                        self.client.initialize(name="pull-supervisor")
                    result, _ = self.client.call(tool, arguments)
                    return json.loads(result["result"]["content"][0]["text"])
                except (OSError, KeyError, ValueError, ConnectionError):
                    self.client = None
                    if attempt == 2:
                        raise

    def state(self):
        return self.call("spike_state")

    def fence(self, worker, generation):
        return self.call("spike_fence", worker=worker, generation=generation)


class Tail:
    """Reads the complete lines a file gained since the last call."""

    def __init__(self, path):
        self.path = pathlib.Path(path)
        self.offset = 0

    def read(self):
        try:
            with open(self.path, "rb") as handle:
                handle.seek(self.offset)
                data = handle.read()
        except OSError:
            return []
        end = data.rfind(b"\n")
        if end < 0:
            return []
        self.offset += end + 1
        records = []
        for line in data[:end].decode("utf-8", "replace").splitlines():
            try:
                records.append(json.loads(line))
            except ValueError:
                continue
        return records


class Slot:
    """One place of the pool: a worker id whose processes follow each other as generations."""

    def __init__(self, worker, role, harness, model, prompt, eager):
        self.worker, self.role, self.harness, self.model = worker, role, harness, model
        self.prompt, self.eager = prompt, eager
        self.generation = 0
        self.proc = None
        self.state = "empty"          # empty | live | ending
        self.hold = False             # the caller starts the next generation itself
        self.no_kill = False          # fence without ending the process (the stalled worker resumes later)
        self.reset()

    def reset(self):
        self.last_ms = now_ms()
        self.context = 0
        self.submits = 0
        self.ack_missed = False
        self.ended = False
        self.output = None

    def live(self):
        return self.state == "live"


class JobRuntime:
    def __init__(self, run_dir, url, spawn, plan, wait_s):
        self.run_dir = pathlib.Path(run_dir)
        self.spawn = spawn
        self.plan = plan
        self.silence_ms = (wait_s + plan["silence_grace_s"]) * 1000
        self.control = Control(url)
        self.events = Tail(self.run_dir / "events.jsonl")
        self.slots = []
        self.processes = []
        self.zombies = []
        self.submits = 0
        self.failed_starts = 0
        self.aborted = None
        self.state = {}
        self.state_at = 0
        self.lock = threading.Lock()
        self.out = open(self.run_dir / "supervisor.jsonl", "a", encoding="utf-8")

    def log(self, event, **fields):
        with self.lock:
            self.out.write(json.dumps({"t_ms": now_ms(), "event": event, **fields}) + "\n")
            self.out.flush()

    def add(self, role, harness, model, prompt, eager, count=1):
        for _ in range(count):
            index = len([slot for slot in self.slots if slot.role == role]) + 1
            self.slots.append(Slot(f"{role[0]}{index}", role, harness, model, prompt, eager))

    def slot(self, worker, generation=None):
        for slot in self.slots:
            if slot.worker == worker and (generation is None or slot.generation == generation):
                return slot
        return None

    # --- one round --------------------------------------------------------------------------------

    def step(self):
        """Reads what happened, detects, acts. Returns the stub's new events."""
        events = self.events.read()
        for event in events:
            slot = self.slot(event.get("connection"), event.get("generation"))
            if slot is None:
                continue
            slot.last_ms = max(slot.last_ms, event["t_ms"])
            if event["event"] == "ack_missed":
                slot.ack_missed = True
            elif event["event"] == "submit":
                slot.submits += 1
                self.submits += 1
            elif event["event"] == "wait_end" and event.get("outcome") == "end":
                slot.ended = True
        if time.time() - self.state_at >= 1:
            self.state = self.control.state()
            self.state_at = time.time()
            for slot in self.slots:
                self.check(slot)
        return events

    def work(self, role):
        work = self.state.get("work", {})
        return work.get(role, 0) + work.get("any", 0) > 0

    def holds(self, slot):
        return any(lease["worker"] == slot.worker for lease in self.state.get("leases", []))

    def check(self, slot):
        if slot.state == "ending":
            return
        if slot.state == "empty":
            first = slot.generation == 0
            if slot.hold or (not first and not self.plan.get("respawn", True)):
                return
            if (first and slot.eager) or self.work(slot.role):
                self.start(slot)
            return
        self.read_output(slot)
        silent_ms = now_ms() - slot.last_ms
        idle = self.state.get("idle", {}).get(slot.worker, 0)
        between_tasks = not self.holds(slot)
        if slot.proc.process.poll() is not None:
            # an exit before any call of the worker is a start failure (login, quota, harness), not a loss
            if not slot.ended and slot.last_ms <= slot.proc.started_ms:
                self.failed_starts += 1
                if self.failed_starts >= MAX_FAILED_STARTS:
                    self.aborted = f"{self.failed_starts} workers in a row exited before their first call"
            else:
                self.failed_starts = 0
            self.end(slot, "ended" if slot.ended else "exit", exit_code=slot.proc.process.returncode)
        elif slot.ack_missed:
            self.end(slot, "ack_missed")
        elif silent_ms > self.silence_ms:
            self.end(slot, "silence", silent_ms=silent_ms)
        elif between_tasks and self.plan.get("idle_wakeups") and idle >= self.plan["idle_wakeups"]:
            self.end(slot, "idle", wakeups=idle)
        elif (between_tasks and self.plan.get("token_budget") and slot.context >= self.plan["token_budget"]
              and slot.submits > 0):      # a budget below the context of a fresh worker must not recycle forever
            self.end(slot, "budget", budget=self.plan["token_budget"])
        elif between_tasks and self.plan.get("recycle_every") and slot.submits >= self.plan["recycle_every"]:
            self.end(slot, "recycle", submits=slot.submits)

    def read_output(self, slot):
        """Context size and compaction markers from the worker's own output."""
        for record in slot.output.read():
            try:
                record["json"] = json.loads(record["line"]) if record.get("s") == "out" else None
            except ValueError:
                continue
            tokens = hx.context_tokens(slot.harness, record)
            if tokens:
                slot.context = tokens
            if hx.compactions([record]):
                self.log("compaction", worker=slot.worker, generation=slot.generation)
                self.control.call("spike_compacted", worker=slot.worker)

    # --- lifecycle --------------------------------------------------------------------------------

    def start(self, slot, prompt=None):
        slot.generation += 1
        slot.reset()
        slot.no_kill = False
        slot.proc = self.spawn(slot, prompt or slot.prompt)
        slot.output = Tail(self.run_dir / f"harness-{slot.proc.tag}.jsonl")
        slot.state = "live"
        slot.last_ms = slot.proc.started_ms
        self.processes.append({"tag": slot.proc.tag, "worker": slot.worker, "generation": slot.generation,
                               "role": slot.role, "harness": slot.harness, "model": slot.model,
                               "pid": slot.proc.process.pid, "started_ms": slot.proc.started_ms})
        self.log("spawn", worker=slot.worker, generation=slot.generation, role=slot.role, harness=slot.harness,
                 pid=slot.proc.process.pid, prompt=prompt or slot.prompt)

    def end(self, slot, reason, **fields):
        """Ends the worker of a slot and fences its generation; the slot is empty afterwards."""
        slot.state = "ending"
        generation, proc = slot.generation, slot.proc
        self.log("detect", worker=slot.worker, generation=generation, reason=reason, last_event_ms=slot.last_ms,
                 context_tokens=slot.context, **fields)
        threading.Thread(target=self._end, args=(slot, generation, proc, reason), daemon=True).start()

    def _end(self, slot, generation, proc, reason):
        pid = proc.process.pid
        if slot.no_kill:
            self.zombies.append(proc)
        else:
            if proc.process.poll() is None:
                self.log("term", worker=slot.worker, generation=generation)
                kill_group(pid, signal.SIGTERM)
                if proc.wait(TERM_GRACE_S) is None:
                    self.log("kill", worker=slot.worker, generation=generation)
            kill_group(pid, signal.SIGKILL)        # whatever is left of the group, the relay included
            proc.wait(5)
        self.log("fence_sent", worker=slot.worker, generation=generation)
        try:
            released = self.control.fence(slot.worker, generation).get("released")
        except (OSError, KeyError, ValueError, ConnectionError) as error:
            released = f"failed: {error}"
        self.log("fence", worker=slot.worker, generation=generation, released=released, reason=reason)
        for entry in self.processes:
            if entry["tag"] == proc.tag:
                entry.update(ended_ms=now_ms(), reason=reason, exit_code=proc.process.returncode)
        slot.state = "empty"

    def wait_empty(self, slot, timeout=60):
        deadline = time.time() + timeout
        while slot.state != "empty" and time.time() < deadline:
            time.sleep(0.2)
        return slot.state == "empty"

    def relay_pids(self, slot):
        found = subprocess.run(["pgrep", "-g", str(slot.proc.process.pid), "-f", "relay.py"],
                               capture_output=True, text=True).stdout.split()
        return [int(pid) for pid in found]

    def close(self, grace_s=20):
        """Lets the workers that were told to end leave by themselves, then ends the rest."""
        deadline = time.time() + grace_s
        while time.time() < deadline and any(s.live() and s.proc.process.poll() is None for s in self.slots):
            time.sleep(0.5)
        for event in self.events.read():           # an `end` answered while the runtime was closing
            slot = self.slot(event.get("connection"))
            if slot and event["event"] == "wait_end" and event.get("outcome") == "end":
                slot.ended = True
        for slot in self.slots:
            if slot.live():
                reason = "ended" if slot.ended and slot.proc.process.poll() is not None else "shutdown"
                self.end(slot, reason)
            slot.hold = True
        for slot in self.slots:
            self.wait_empty(slot, TERM_GRACE_S + 15)
        for proc in self.zombies:
            kill_group(proc.process.pid, signal.SIGCONT)
            kill_group(proc.process.pid, signal.SIGKILL)
        return self.processes
