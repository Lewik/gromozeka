"""Async client for Gromozeka's stateless HTTP MCP profile.

Use the Worker's managed Python environment. The Harness supplies the endpoint,
command-scoped credential, routing context and configured CA automatically.

    async with Client.from_env() as client:
        tools = await client.list_tools()
        schema = await client.describe_tool(exact_name)
        result = await client.call(exact_name, arguments)

Independent calls on one client can run with asyncio.gather. Calls are never
retried automatically. A cancelled await does not undo a remote tool's effects.
The client belongs to one event loop; close it with async with or await close().
"""
import asyncio
import copy
import http.cookiejar
import ipaddress
import json
import math
import os
import ssl
import sys
import urllib.parse

import certifi
import httpx

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


class _StatelessCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def set_ok(self, cookie, request):
        return False

    def return_ok(self, cookie, request):
        return False


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
    """One script's async MCP client. Use async with and await its operations.

    call() returns the COMPLETE MCP result dict (content, structuredContent, isError,
    and extension fields). PENDING/task receipts remain receipts, not completed work.
    Tool errors raise ToolError carrying that same result unless check=False.
    Exact names only: use list_tools()/describe_tool(), never guess a version suffix.
    """
    def __init__(self, url, token, *, context=None, ca_bundle=None, timeout=60, max_response_bytes=_MAX_RESPONSE_BYTES):
        try:
            parts = urllib.parse.urlsplit(url)
            parts.port
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
        if ca_bundle is not None and (not isinstance(ca_bundle, str) or not ca_bundle.strip() or "\x00" in ca_bundle):
            raise ConfigurationError("MCP CA bundle must be a nonempty file path")
        self._url, self._token, self._ca_bundle = url, token, ca_bundle
        self._context = _object(context if context is not None else {}, "Context")
        if self._context.keys() - _CONTEXT_FIELDS:
            raise ConfigurationError("Unsupported context selector")
        self._timeout, self._max_bytes = timeout, max_response_bytes
        self._version = None
        self._next_id = 0
        self._tools = None
        self._closed = False
        self._http = None
        self._loop = None
        self._initialize_lock = asyncio.Lock()
        self._catalog_lock = asyncio.Lock()

    @classmethod
    def from_env(cls, *, context=None, **options):
        """context={} disables inherited routing defaults, not Server-bound identity."""
        if context is None:
            try:
                context = _json_loads(os.environ.get("GRZ_MCP_CONTEXT", "{}"))
            except (ValueError, TypeError):
                raise ConfigurationError("GRZ_MCP_CONTEXT must be a JSON object") from None
        if "ca_bundle" not in options and os.environ.get("GRZ_MCP_CA_BUNDLE"):
            options["ca_bundle"] = os.environ["GRZ_MCP_CA_BUNDLE"]
        return cls(os.environ.get("GRZ_MCP_URL", ""), os.environ.get("GRZ_MCP_TOKEN", ""),
                   context=context, **options)

    async def __aenter__(self):
        try:
            await self.initialize()
        except BaseException:
            await self.close()
            raise
        return self

    async def __aexit__(self, *_):
        await self.close()

    async def close(self):
        self._closed = True
        self._token = None
        self._tools = None
        if self._http is not None:
            await self._http.aclose()

    def _require_open(self):
        if self._closed:
            raise ConfigurationError("MCP client is closed")
        loop = asyncio.get_running_loop()
        if self._loop is None:
            self._loop = loop
        elif self._loop is not loop:
            raise ConfigurationError("MCP client cannot be shared between event loops")

    async def initialize(self):
        self._require_open()
        async with self._initialize_lock:
            self._require_open()
            if self._version is None:
                result = await self._request("initialize", {
                    "protocolVersion": _SUPPORTED_VERSIONS[0], "capabilities": {},
                    "clientInfo": {"name": "gromozeka-script-sdk", "version": "2"}})
                version = result.get("protocolVersion")
                if version not in _SUPPORTED_VERSIONS:
                    raise ProtocolError("Unsupported MCP protocol version")
                self._version = version
                try:
                    await self._request("notifications/initialized", notification=True)
                except BaseException:
                    self._version = None
                    raise
            return self._version

    async def list_tools(self, *, refresh=False):
        await self.initialize()
        async with self._catalog_lock:
            self._require_open()
            if self._tools is None or refresh:
                tools, seen, cursor = {}, set(), None
                for _ in range(1000):
                    page = await self._request("tools/list", {} if cursor is None else {"cursor": cursor})
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

    async def describe_tool(self, name):
        tools = await self.list_tools()
        for tool in tools:
            if tool["name"] == name:
                return tool
        raise ConfigurationError("Exact tool name is not in this catalog; explicitly refresh list_tools()")

    async def call(self, name, arguments=None, *, context=None, check=True):
        """Explicit context or arguments['_grz'] REPLACES defaults, never merges scopes.

        Without an override, only defaults declared by this tool's _grz schema are
        forwarded. The Server still resolves/authorizes every call; a catalog is not
        a permission grant. No schema rewriting, logical-name fallback or call retry.
        """
        args = _object(arguments if arguments is not None else {}, "Arguments")
        if context is not None and "_grz" in args:
            raise ConfigurationError("Supply context or _grz, not both")
        tool = await self.describe_tool(name)
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
        result = await self._request("tools/call", {"name": name, "arguments": args})
        if (not isinstance(result.get("content"), list) or
                not isinstance(result.get("isError", False), bool)):
            raise ProtocolError(outcome_unknown=True)
        if result.get("isError") and check:
            raise ToolError(result)
        return result

    async def _request(self, method, params=None, *, notification=False):
        self._require_open()
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
                   "Accept": "application/json, text/event-stream", "Accept-Encoding": "identity",
                   "Connection": "close"}
        if self._version:
            headers["MCP-Protocol-Version"] = self._version
        if self._http is None:
            # Stateless requests never replay through a stale pooled connection or carry cookies.
            self._http = httpx.AsyncClient(
                verify=self._ssl_context(), timeout=self._timeout, trust_env=False,
                follow_redirects=False, cookies=http.cookiejar.CookieJar(policy=_StatelessCookiePolicy()),
                limits=httpx.Limits(max_connections=20, max_keepalive_connections=0),
            )
        uncertain = method == "tools/call"
        try:
            async with self._http.stream("POST", self._url, content=body, headers=headers) as response:
                if notification:
                    if response.status_code not in (202, 204):
                        raise HttpError(response.status_code)
                    return {}
                if response.status_code != 200:
                    raise HttpError(response.status_code,
                                    outcome_unknown=uncertain and response.status_code not in (401, 403))
                media = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                if media == "application/json":
                    data = b"".join([chunk async for chunk in self._response_chunks(response)])
                    value = _json_loads(data.decode("utf-8"))
                elif media == "text/event-stream":
                    value = await self._sse_response(response, request_id)
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
        except (OSError, httpx.HTTPError):
            raise TransportError(outcome_unknown=uncertain) from None
        except (ValueError, UnicodeError):
            raise ProtocolError(outcome_unknown=uncertain) from None

    def _ssl_context(self):
        try:
            context = ssl.create_default_context()
            # A private Python distribution may not have a usable system CA file.
            context.load_verify_locations(cafile=certifi.where())
            if self._ca_bundle is not None:
                context.load_verify_locations(cafile=self._ca_bundle)
            return context
        except (OSError, ValueError):
            raise ConfigurationError("MCP TLS trust configuration is invalid") from None

    async def _response_chunks(self, response):
        total = 0
        async for chunk in response.aiter_raw():
            total += len(chunk)
            if total > self._max_bytes:
                raise ProtocolError("MCP response limit exceeded")
            yield chunk

    async def _sse_response(self, response, request_id):
        # POST response only. Never opens GET, resumes event IDs, polls, or retries.
        data, pending = [], b""

        def consume(line):
            if line:
                if line.startswith("data:"):
                    data.append(line[5:].removeprefix(" "))
                return None
            if not data:
                return None
            event = _json_loads("\n".join(data))
            data.clear()
            if not isinstance(event, dict):
                raise ProtocolError()
            if "id" in event:
                if "method" in event or event["id"] != request_id:
                    raise ProtocolError("Unsupported MCP server request or response ID")
                return event
            if event.get("jsonrpc") != "2.0" or "method" not in event:
                raise ProtocolError()
            return None

        async for chunk in self._response_chunks(response):
            pending += chunk
            while b"\n" in pending:
                line, pending = pending.split(b"\n", 1)
                value = consume(line.rstrip(b"\r").decode("utf-8"))
                if value is not None:
                    return value
        if pending:
            consume(pending.rstrip(b"\r").decode("utf-8"))
        value = consume("")
        if value is not None:
            return value
        raise ProtocolError("MCP stream ended before response")


async def _main(argv):
    try:
        if not argv or argv[0] not in ("list", "describe", "call"):
            raise ConfigurationError("Usage: grz.py list | describe NAME | call NAME (JSON arguments on stdin)")
        operation = argv[0]
        if len(argv) != (1 if operation == "list" else 2):
            raise ConfigurationError("Invalid SDK command arguments")
        async with Client.from_env() as client:
            if operation == "list":
                result = await client.list_tools()
            elif operation == "describe":
                result = await client.describe_tool(argv[1])
            else:
                try:
                    args = _json_loads(sys.stdin.read())
                except (ValueError, UnicodeError):
                    raise ConfigurationError("Arguments on stdin must be JSON") from None
                result = await client.call(argv[1], args)
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
    sys.exit(asyncio.run(_main(sys.argv[1:])))
