"""Minimal MCP client over Streamable HTTP (stdlib only), used by `pull.py selfcheck`.

It proves that the stub itself holds a blocking tool call for the full measurement cap, so that an
abort seen in V1 is the harness's and not the server's.
"""
import http.client
import json
import urllib.parse

PROTOCOL = "2025-06-18"


class McpClient:
    def __init__(self, url, timeout):
        self.url = urllib.parse.urlparse(url)
        self.timeout = timeout
        self.session = None
        self.next_id = 0

    def _post(self, message):
        connection = http.client.HTTPConnection(self.url.hostname, self.url.port, timeout=self.timeout)
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "MCP-Protocol-Version": PROTOCOL}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        connection.request("POST", self.url.path, json.dumps(message), headers)
        response = connection.getresponse()
        self.session = response.getheader("Mcp-Session-Id") or self.session
        return connection, response

    def notify(self, method):
        connection, response = self._post({"jsonrpc": "2.0", "method": method})
        response.read()
        connection.close()

    def request(self, method, params=None):
        """Returns (result message, notifications received before it)."""
        self.next_id += 1
        message = {"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params or {}}
        connection, response = self._post(message)
        notifications = []
        try:
            if "text/event-stream" not in (response.getheader("Content-Type") or ""):
                return json.loads(response.read()), notifications
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if not line.startswith("data:"):
                    continue
                event = json.loads(line[5:].strip())
                if event.get("id") == self.next_id:
                    return event, notifications
                notifications.append(event)
            raise ConnectionError("stream ended before the response arrived")
        finally:
            connection.close()

    def initialize(self):
        result, _ = self.request("initialize", {
            "protocolVersion": PROTOCOL, "capabilities": {},
            "clientInfo": {"name": "pull-selfcheck", "version": "0"}})
        self.notify("notifications/initialized")
        return result

    def call(self, tool, arguments=None, progress_token=None):
        params = {"name": tool, "arguments": arguments or {}}
        if progress_token:
            params["_meta"] = {"progressToken": progress_token}
        return self.request("tools/call", params)
