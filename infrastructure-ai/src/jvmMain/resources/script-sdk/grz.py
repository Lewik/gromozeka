"""Dependency-free synchronous client for Gromozeka's stateless HTTP MCP profile.

Python 3.9+. No OAuth/browser/Keychain, stdio transport, automatic retries or polling.
Credentials are explicit: GRZ_MCP_URL and GRZ_MCP_TOKEN (an existing mcp:control PAT).
GRZ_MCP_CONTEXT contains routing defaults, NOT an authorization grant.
"""
import copy
import http.client
import ipaddress
import json
import math
import os
import ssl
import sys
import urllib.parse

__all__ = ["Client", "Error", "ConfigurationError", "TransportError", "HttpError",
           "ProtocolError", "ToolError"]
_SUPPORTED_VERSIONS = ("2025-11-25",)
_CONTEXT_FIELDS = frozenset(("project_id", "conversation_id", "agent_id", "worker_id",
                            "workspace_mount_id", "delivery_ttl_seconds",
                            "execution_timeout_seconds", "wait_timeout_seconds"))
_MAX_RESPONSE_BYTES = 16 * 1024 * 1024


class Error(Exception):
    """A bounded diagnostic; response bodies, credentials and arguments are never formatted."""
    outcome_unknown = False


class ConfigurationError(Error):
    pass


class TransportError(Error):
    def __init__(self, outcome_unknown=False):
        self.outcome_unknown = outcome_unknown
        super().__init__("MCP transport failed" +
                         ("; operation outcome may be unknown; do not resubmit" if outcome_unknown else ""))


class HttpError(Error):
    def __init__(self, status, outcome_unknown=False):
        self.status = status
        self.outcome_unknown = outcome_unknown
        super().__init__("MCP HTTP status %d%s" % (status,
                         "; operation outcome may be unknown; do not resubmit" if outcome_unknown else ""))


class ProtocolError(Error):
    def __init__(self, message="Invalid MCP response", *, code=None, outcome_unknown=False):
        self.code = code
        self.outcome_unknown = outcome_unknown
        super().__init__(message + ("; operation outcome may be unknown; do not resubmit" if outcome_unknown else ""))


class ToolError(Error):
    def __init__(self, result):
        self.result = result
        super().__init__("MCP tool returned isError=true; inspect exception.result explicitly")


def _object(value, label):
    if not isinstance(value, dict) or any(not isinstance(k, str) for k in value):
        raise ConfigurationError(label + " must be a JSON object")
    return copy.deepcopy(value)


def _json_loads(text):
    def invalid_constant(_):
        raise ValueError("Non-JSON constant")
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result
    return json.loads(text, parse_constant=invalid_constant, object_pairs_hook=unique_keys)


class Client:
    """One script's synchronous client, not thread-safe. Close via `with`.

    call() returns the COMPLETE MCP result dict (content, structuredContent, isError,
    and extension fields). PENDING/task receipts remain receipts, not completed work.
    Tool errors raise ToolError carrying that same result unless check=False.
    Exact names only: use list_tools()/describe_tool(), never guess a version suffix.
    """
    def __init__(self, url, token, *, context=None, timeout=60, max_response_bytes=_MAX_RESPONSE_BYTES):
        try:
            parts = urllib.parse.urlsplit(url)
            port = parts.port
            host = parts.hostname
            loopback = host == "localhost"
            if host and not loopback:
                try:
                    loopback = ipaddress.ip_address(host).is_loopback
                except ValueError:
                    pass
            valid = (parts.scheme in ("https", "http") and host and
                     (parts.scheme == "https" or loopback) and
                     not parts.username and not parts.password and not parts.query and not parts.fragment and
                     all(32 < ord(c) < 127 for c in url))
        except (ValueError, TypeError, AttributeError):
            valid = False
        if not valid:
            raise ConfigurationError("MCP URL must be HTTPS (HTTP only on loopback), without credentials, query or fragment")
        if not isinstance(token, str) or not token or len(token) > 8192 or any(not 32 < ord(c) < 127 for c in token):
            raise ConfigurationError("MCP bearer token must be nonempty printable ASCII without whitespace")
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
            raise ConfigurationError("MCP timeout must be a positive number")
        if isinstance(max_response_bytes, bool) or not isinstance(max_response_bytes, int) or max_response_bytes < 1:
            raise ConfigurationError("MCP response limit must be a positive integer")
        self._scheme, self._host, self._port = parts.scheme, host, port
        self._path = parts.path or "/"
        self._token = token
        self._context = _object(context if context is not None else {}, "Context")
        if self._context.keys() - _CONTEXT_FIELDS:
            raise ConfigurationError("Unsupported context selector")
        self._timeout, self._max_bytes = timeout, max_response_bytes
        self._version = None
        self._next_id = 0
        self._tools = None
        self._closed = False

    @classmethod
    def from_env(cls, *, context=None, **options):
        """context={} deliberately disables ALL inherited routing defaults."""
        if context is None:
            try:
                context = _json_loads(os.environ.get("GRZ_MCP_CONTEXT", "{}"))
            except (ValueError, TypeError):
                raise ConfigurationError("GRZ_MCP_CONTEXT must be a JSON object") from None
        return cls(os.environ.get("GRZ_MCP_URL", ""), os.environ.get("GRZ_MCP_TOKEN", ""),
                   context=context, **options)

    def __enter__(self):
        self.initialize()
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        self._closed = True
        self._token = None
        self._tools = None

    def initialize(self):
        if self._closed:
            raise ConfigurationError("MCP client is closed")
        if self._version is None:
            result = self._request("initialize", {
                "protocolVersion": _SUPPORTED_VERSIONS[0], "capabilities": {},
                "clientInfo": {"name": "gromozeka-script-sdk", "version": "1"}})
            version = result.get("protocolVersion")
            if version not in _SUPPORTED_VERSIONS:
                raise ProtocolError("Unsupported MCP protocol version")
            self._version = version
            try:
                self._request("notifications/initialized", notification=True)
            except Error:
                self._version = None
                raise
        return self._version

    def list_tools(self, *, refresh=False):
        self.initialize()
        if self._tools is None or refresh:
            tools, seen, cursor = {}, set(), None
            for _ in range(1000):
                page = self._request("tools/list", {} if cursor is None else {"cursor": cursor})
                if not isinstance(page.get("tools"), list):
                    raise ProtocolError()
                for tool in page["tools"]:
                    if (not isinstance(tool, dict) or not isinstance(tool.get("name"), str) or
                            not tool["name"] or not isinstance(tool.get("inputSchema"), dict) or tool["name"] in tools):
                        raise ProtocolError()
                    tools[tool["name"]] = tool
                cursor = page.get("nextCursor")
                if cursor is None:
                    self._tools = tools
                    break
                if not isinstance(cursor, str) or not cursor or cursor in seen:
                    raise ProtocolError("Invalid MCP tools pagination")
                seen.add(cursor)
            else:
                raise ProtocolError("MCP tools pagination limit exceeded")
        return copy.deepcopy(list(self._tools.values()))

    def describe_tool(self, name):
        self.list_tools()
        if name not in self._tools:
            raise ConfigurationError("Exact tool name is not in this catalog; explicitly refresh list_tools()")
        return copy.deepcopy(self._tools[name])

    def call(self, name, arguments=None, *, context=None, check=True):
        """Explicit context or arguments['_grz'] REPLACES defaults, never merges scopes.

        Without an override, only defaults declared by this tool's _grz schema are
        forwarded. The Server still resolves/authorizes every call; a catalog is not
        a permission grant. No schema rewriting, logical-name fallback or call retry.
        """
        args = _object(arguments if arguments is not None else {}, "Arguments")
        if context is not None and "_grz" in args:
            raise ConfigurationError("Supply context or _grz, not both")
        tool = self.describe_tool(name)
        properties = tool["inputSchema"].get("properties", {})
        if not isinstance(properties, dict):
            raise ProtocolError("Invalid MCP tool schema")
        envelope = properties.get("_grz", {})
        if not isinstance(envelope, dict) or not isinstance(envelope.get("properties", {}), dict):
            raise ProtocolError("Invalid MCP tool context schema")
        allowed = envelope.get("properties", {})
        if context is not None or "_grz" in args:
            selected = _object(context if context is not None else args.pop("_grz"), "Context")
            if selected.keys() - allowed.keys():
                raise ConfigurationError("Context selector is not declared by this tool")
        else:
            selected = {k: v for k, v in self._context.items() if k in allowed}
        if selected:
            args["_grz"] = selected
        result = self._request("tools/call", {"name": name, "arguments": args})
        if (not isinstance(result.get("content"), list) or
                not isinstance(result.get("isError", False), bool)):
            raise ProtocolError(outcome_unknown=True)
        if result.get("isError") and check:
            raise ToolError(result)
        return result

    def _request(self, method, params=None, *, notification=False):
        if self._closed:
            raise ConfigurationError("MCP client is closed")
        self._next_id += 1
        request_id = self._next_id
        message = {"jsonrpc": "2.0", "method": method}
        if not notification:
            message["id"] = request_id
        if params is not None:
            message["params"] = params
        try:
            body = json.dumps(message, ensure_ascii=False, allow_nan=False).encode("utf-8")
        except (TypeError, ValueError, UnicodeError):
            raise ConfigurationError("Request must contain valid JSON and Unicode") from None
        headers = {"Authorization": "Bearer " + self._token, "Content-Type": "application/json",
                   "Accept": "application/json, text/event-stream", "Connection": "close"}
        if self._version:
            headers["MCP-Protocol-Version"] = self._version
        # A fresh connection per HTTP request avoids implicit stale-connection replays.
        # http.client does not follow redirects, send cookies, or use ambient proxies.
        connection = (http.client.HTTPSConnection(self._host, self._port, timeout=self._timeout,
                                                 context=ssl.create_default_context())
                      if self._scheme == "https" else
                      http.client.HTTPConnection(self._host, self._port, timeout=self._timeout))
        uncertain = method == "tools/call"
        try:
            connection.request("POST", self._path, body, headers)
            response = connection.getresponse()
            if notification:
                if response.status not in (202, 204):
                    raise HttpError(response.status)
                return {}
            if response.status != 200:
                raise HttpError(response.status, outcome_unknown=uncertain and response.status not in (401, 403))
            media = response.getheader("Content-Type", "").split(";", 1)[0].strip().lower()
            if media == "application/json":
                data = response.read(self._max_bytes + 1)
                if len(data) > self._max_bytes:
                    raise ProtocolError("MCP response limit exceeded", outcome_unknown=uncertain)
                value = _json_loads(data.decode("utf-8"))
            elif media == "text/event-stream":
                value = self._sse_response(response, request_id)
            else:
                raise ProtocolError("Unsupported MCP response type", outcome_unknown=uncertain)
            if (not isinstance(value, dict) or value.get("jsonrpc") != "2.0" or
                    type(value.get("id")) is not int or value["id"] != request_id):
                raise ProtocolError(outcome_unknown=uncertain)
            if "error" in value:
                if "result" in value or not isinstance(value["error"], dict):
                    raise ProtocolError(outcome_unknown=uncertain)
                code = value["error"].get("code")
                raise ProtocolError("MCP JSON-RPC error", code=code if type(code) is int else None,
                                    outcome_unknown=uncertain)
            if not isinstance(value.get("result"), dict):
                raise ProtocolError(outcome_unknown=uncertain)
            return value["result"]
        except ProtocolError as error:
            if uncertain and not error.outcome_unknown:
                raise ProtocolError("Invalid MCP response", code=error.code, outcome_unknown=True) from None
            raise
        except Error:
            raise
        except (OSError, http.client.HTTPException):
            raise TransportError(outcome_unknown=uncertain) from None
        except (ValueError, UnicodeError):
            raise ProtocolError(outcome_unknown=uncertain) from None
        finally:
            connection.close()

    def _sse_response(self, response, request_id):
        # POST response only. Never opens GET, resumes event IDs, polls, or retries.
        data, total = [], 0
        while True:
            raw = response.readline(self._max_bytes - total + 1)
            total += len(raw)
            if total > self._max_bytes:
                raise ProtocolError("MCP response limit exceeded", outcome_unknown=True)
            line = raw.decode("utf-8").rstrip("\r\n")
            if not line:
                if data:
                    event = _json_loads("\n".join(data))
                    data = []
                    if not isinstance(event, dict):
                        raise ProtocolError()
                    if "id" in event:
                        if "method" in event or event["id"] != request_id:
                            raise ProtocolError("Unsupported MCP server request or response ID", outcome_unknown=True)
                        return event
                    if event.get("jsonrpc") != "2.0" or "method" not in event:
                        raise ProtocolError()
                    # Notifications may precede the single response; no tool execution here.
                if not raw:
                    raise ProtocolError("MCP stream ended before response", outcome_unknown=True)
            elif line.startswith("data:"):
                data.append(line[5:].removeprefix(" "))


def _main(argv):
    try:
        if not argv or argv[0] not in ("list", "describe", "call"):
            raise ConfigurationError("Usage: grz.py list | describe NAME | call NAME (JSON arguments on stdin)")
        operation = argv[0]
        if len(argv) != (1 if operation == "list" else 2):
            raise ConfigurationError("Invalid SDK command arguments")
        with Client.from_env() as client:
            if operation == "list":
                result = client.list_tools()
            elif operation == "describe":
                result = client.describe_tool(argv[1])
            else:
                try:
                    args = _json_loads(sys.stdin.read())
                except (ValueError, UnicodeError):
                    raise ConfigurationError("Arguments on stdin must be JSON") from None
                result = client.call(argv[1], args)
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 0
    except ToolError as error:
        print(json.dumps(error.result, ensure_ascii=False, allow_nan=False))
        print(str(error), file=sys.stderr)
        return 2
    except Error as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
