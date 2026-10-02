#!/usr/bin/env python3
"""STDIO relay to the pull-mechanism stub: the stand-in for `pm-mcp serve`.

Usage:
  relay.py --url http://127.0.0.1:<port>/mcp --worker <id> --log <file>

A host starts this program as its MCP server and speaks newline-delimited JSON-RPC on stdin and stdout,
exactly as it will with `pm-mcp serve`. The relay forwards every message to the stub over Streamable HTTP
and writes back whatever the stub answers, including the progress notifications on a call's own stream.

Like the real relay it carries the caller's identity: it sets the argument `worker` of every `pull_*`
tool call to its own worker id, so the model never has to pass it.

Everything the host does is written to the log with its arrival time (requests, cancellations, the end of
stdin, a signal). This is the host-side observation point of V1: it shows when a host gives a call up,
which the stub cannot see.
"""
import argparse
import http.client
import json
import signal
import sys
import threading
import time
import urllib.parse

OUT_LOCK = threading.Lock()
LOG_LOCK = threading.Lock()


class Relay:
    def __init__(self, url, worker, log_path):
        self.url = urllib.parse.urlparse(url)
        self.worker = worker
        self.log_file = open(log_path, "a", encoding="utf-8")
        self.session = None
        self.protocol = None
        self.pending = {}

    def log(self, event, **fields):
        with LOG_LOCK:
            self.log_file.write(json.dumps({"t_ms": int(time.time() * 1000), "event": event,
                                            "worker": self.worker, **fields}) + "\n")
            self.log_file.flush()

    def emit(self, message):
        with OUT_LOCK:
            sys.stdout.write(json.dumps(message) + "\n")
            sys.stdout.flush()

    def headers(self, message=None):
        """HTTP headers of one forwarded message.

        A host that calls without a session (protocol 2026-07-28) puts its protocol version, client info and
        capabilities into `params._meta` of every request; the HTTP transport additionally wants the method
        and, for a call by name, the name as headers. A relay has to derive them, the host does not send them.
        """
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        protocol = self.protocol
        if message is not None and message.get("method"):
            headers["Mcp-Method"] = message["method"]
            params = message.get("params") if isinstance(message.get("params"), dict) else {}
            if isinstance(params.get("name"), str):
                headers["Mcp-Name"] = params["name"]
            meta = params.get("_meta") if isinstance(params.get("_meta"), dict) else {}
            protocol = meta.get("io.modelcontextprotocol/protocolVersion") or protocol
        if protocol:
            headers["MCP-Protocol-Version"] = protocol
        return headers

    def forward(self, message):
        request_id = message.get("id")
        is_request = "method" in message and request_id is not None
        try:
            connection = http.client.HTTPConnection(self.url.hostname, self.url.port, timeout=4 * 3600)
            connection.request("POST", self.url.path, json.dumps(message), self.headers(message))
            response = connection.getresponse()
            if message.get("method") == "initialize" and response.getheader("Mcp-Session-Id"):
                self.session = response.getheader("Mcp-Session-Id")
            if not is_request:
                # A notification never gets an answer on stdio, whatever the HTTP transport returns for it.
                body = response.read()
                if response.status >= 400:
                    self.log("notification_refused", method=message.get("method"), status=response.status,
                             body=body.decode("utf-8", "replace")[:300])
            elif "text/event-stream" in (response.getheader("Content-Type") or ""):
                for raw in response:
                    line = raw.decode("utf-8").rstrip("\r\n")
                    if not line.startswith("data:"):
                        continue
                    event = json.loads(line[5:].strip())
                    self.note(message, event)
                    self.emit(event)
                    if is_request and event.get("id") == request_id and "method" not in event:
                        break
                else:
                    # the stub's stream ended without the answer: the host must not wait for it
                    self.log("stream_ended", id=request_id)
                    self.error(request_id, "the stub closed the stream before it answered")
            else:
                body = response.read()
                if body.strip():
                    answer = json.loads(body)
                    self.note(message, answer)
                    self.emit(answer)
                elif is_request:
                    self.error(request_id, f"stub answered HTTP {response.status} without a body")
            connection.close()
        except (OSError, ValueError, http.client.HTTPException) as error:
            self.log("forward_failed", id=request_id, error=str(error))
            if is_request:
                self.error(request_id, f"relay could not reach the stub: {error}")
        finally:
            if is_request:
                self.pending.pop(request_id, None)
                self.log("answered", id=request_id)

    def note(self, message, answer):
        if message.get("method") == "initialize" and answer.get("id") == message.get("id"):
            self.protocol = (answer.get("result") or {}).get("protocolVersion") or self.protocol

    def error(self, request_id, text):
        self.emit({"jsonrpc": "2.0", "id": request_id, "error": {"code": -32000, "message": text}})

    def listen(self):
        """Server-initiated messages outside a call (for example tools/list_changed)."""
        try:
            connection = http.client.HTTPConnection(self.url.hostname, self.url.port, timeout=4 * 3600)
            headers = self.headers()
            headers["Accept"] = "text/event-stream"
            connection.request("GET", self.url.path, headers=headers)
            response = connection.getresponse()
            if response.status != 200:
                return
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if line.startswith("data:"):
                    self.emit(json.loads(line[5:].strip()))
        except (OSError, ValueError, http.client.HTTPException):
            return

    def run(self):
        self.log("start")
        for raw in sys.stdin:
            raw = raw.strip()
            if not raw:
                continue
            try:
                message = json.loads(raw)
            except ValueError:
                self.log("unparsable", line=raw[:200])
                continue
            method = message.get("method")
            params = message.get("params") if isinstance(message.get("params"), dict) else {}
            fields = {"method": method, "id": message.get("id")}
            meta = params.get("_meta") if isinstance(params.get("_meta"), dict) else {}
            if "io.modelcontextprotocol/protocolVersion" in meta:
                fields.update(stateless=True, protocol_version=meta["io.modelcontextprotocol/protocolVersion"])
            if method == "server/discover":
                fields.update(client_info=meta.get("io.modelcontextprotocol/clientInfo"))
            if method == "initialize":
                fields.update(client_info=params.get("clientInfo"), protocol_version=params.get("protocolVersion"))
            elif method == "tools/call":
                fields.update(tool=params.get("name"), progress_token="progressToken" in (params.get("_meta") or {}))
                if str(params.get("name", "")).startswith("pull_"):
                    params.setdefault("arguments", {})["worker"] = self.worker
            elif method == "notifications/cancelled":
                fields.update(request_id=params.get("requestId"), reason=params.get("reason"))
            self.log("rx", **fields)
            if method is not None and message.get("id") is not None:
                self.pending[message["id"]] = method
            threading.Thread(target=self.forward, args=(message,), daemon=True).start()
            if method == "notifications/initialized":
                threading.Thread(target=self.listen, daemon=True).start()
        self.log("stdin_closed", pending=list(self.pending.values()))
        time.sleep(0.3)      # let an answer that is already on its way reach the host


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", required=True)
    parser.add_argument("--worker", required=True)
    parser.add_argument("--log", required=True)
    args = parser.parse_args(argv)
    relay = Relay(args.url, args.worker, args.log)

    def on_signal(number, _frame):
        relay.log("signal", signal=number, pending=list(relay.pending.values()))
        sys.exit(128 + number)

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)
    relay.run()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
