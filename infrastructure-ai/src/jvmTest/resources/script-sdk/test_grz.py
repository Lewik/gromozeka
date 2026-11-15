"""Offline protocol/CLI regressions. Only a synthetic loopback HTTP server; no user data."""
import asyncio
import contextlib
import http.server
import io
import json
import os
import pathlib
import shlex
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.environ["GRZ_SDK_HOME"])
import grz

TOKEN = "synthetic-secret-never-print"
RAW = {"content": [{"type": "resource", "resource": {"uri": "test:binary", "mimeType": "application/octet-stream", "blob": "AP8="}}],
       "structuredContent": {"status": "PENDING", "request_id": "synthetic-request"}, "isError": False, "extension": 7}
TOOLS = [
    {"name": "global__v2", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "workspace__v3", "inputSchema": {"type": "object", "properties": {
        "text": {"type": "string"}, "_grz": {"type": "object", "properties": {
            "workspace_mount_id": {"type": "string"}, "conversation_id": {"type": "string"},
            "agent_id": {"type": "string"}}}}}},
]


class Server:
    def __init__(self):
        self.requests = []
        self.call_result = RAW
        self.call_behavior = None
        self.list_behavior = None
        self.version = "2025-11-25"
        owner = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                owner.requests.append((request, dict(self.headers), self.path))
                method = request["method"]
                if method == "notifications/initialized":
                    self.send_response(202); self.end_headers(); return
                if method == "initialize":
                    result = {"protocolVersion": owner.version, "capabilities": {"tools": {}}, "serverInfo": {"name": "fixture", "version": "1"}}
                elif method == "tools/list":
                    result = owner.list_behavior(request) if owner.list_behavior else {"tools": TOOLS}
                else:
                    if owner.call_behavior:
                        owner.call_behavior(self, request); return
                    result = owner.call_result
                self.respond(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}).encode())
            def respond(self, body=b"", status=200, media="application/json", extra=None):
                self.send_response(status)
                self.send_header("Content-Type", media)
                for key, value in (extra or {}).items():
                    self.send_header(key, value)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        self.http = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.http.serve_forever)
        self.thread.start()
        self.url = "http://127.0.0.1:%d/mcp" % self.http.server_port
    def close(self):
        self.http.shutdown(); self.http.server_close(); self.thread.join(5)
    def calls(self):
        return [r for r, _, _ in self.requests if r["method"] == "tools/call"]


class SdkTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.server = Server()
        self.addCleanup(self.server.close)
        self.client = grz.Client(self.server.url, TOKEN, context={
            "conversation_id": "current", "project_id": "project", "workspace_mount_id": "mount",
            "agent_id": "agent", "worker_id": "worker"})
        self.addAsyncCleanup(self.client.close)
    async def test_handshake_and_complete_result(self):
        self.assertEqual(RAW, await self.client.call("global__v2"))
        methods = [r["method"] for r, _, _ in self.server.requests]
        self.assertEqual(["initialize", "notifications/initialized", "tools/list", "tools/call"], methods)
        for request, headers, path in self.server.requests:
            self.assertEqual("Bearer " + TOKEN, headers["Authorization"])
            self.assertEqual("/mcp", path)
            self.assertNotIn("Cookie", headers)
            if request["method"] != "initialize":
                self.assertEqual("2025-11-25", headers["MCP-Protocol-Version"])
        self.assertEqual({}, self.server.calls()[0]["params"]["arguments"])
    async def test_defaults_projected_to_exact_schema_not_authority(self):
        await self.client.call("workspace__v3", {"text": "  שלום\r\n😀  "})
        args = self.server.calls()[0]["params"]["arguments"]
        self.assertEqual({"conversation_id": "current", "workspace_mount_id": "mount", "agent_id": "agent"}, args["_grz"])
        self.assertEqual("  שלום\r\n😀  ", args["text"])
    async def test_explicit_context_replaces_not_merges_and_does_not_mutate_arguments(self):
        args = {"text": "x", "_grz": {"workspace_mount_id": "other"}}
        await self.client.call("workspace__v3", args)
        self.assertEqual(args, self.server.calls()[0]["params"]["arguments"])
        await self.client.call("workspace__v3", {"text": "y"}, context={})
        self.assertEqual({"text": "y"}, self.server.calls()[1]["params"]["arguments"])
        with self.assertRaises(grz.ConfigurationError):
            await self.client.call("workspace__v3", args, context={})
        with self.assertRaises(grz.ConfigurationError):
            await self.client.call("workspace__v3", context={"user_id": "forged"})
        self.assertEqual(2, len(self.server.calls()))
    async def test_null_context_is_not_silently_defaulted(self):
        with self.assertRaises(grz.ConfigurationError):
            await self.client.call("workspace__v3", {"_grz": None})
        self.assertEqual([], self.server.calls())
    async def test_exact_names_and_explicit_refresh(self):
        with self.assertRaises(grz.ConfigurationError):
            await self.client.call("global")
        self.assertEqual([], self.server.calls())
        tools = await self.client.list_tools()
        tools[0]["name"] = "tampered"
        self.assertEqual("global__v2", (await self.client.describe_tool("global__v2"))["name"])
        await self.client.list_tools(refresh=True)
        self.assertEqual(2, sum(r["method"] == "tools/list" for r, _, _ in self.server.requests))
    async def test_pagination(self):
        self.server.list_behavior = lambda r: ({"tools": [TOOLS[0]], "nextCursor": "page2"}
            if "cursor" not in r["params"] else {"tools": [TOOLS[1]]})
        self.assertEqual(TOOLS, await self.client.list_tools())
    async def test_repeated_cursor_is_rejected(self):
        self.server.list_behavior = lambda r: {"tools": [], "nextCursor": "loop"}
        with self.assertRaises(grz.ProtocolError):
            await self.client.list_tools()
        self.assertEqual(2, sum(r["method"] == "tools/list" for r, _, _ in self.server.requests))
    async def test_error_keeps_full_result_without_secret_in_exception(self):
        self.server.call_result = {"content": [{"type": "text", "text": TOKEN}], "isError": True, "structuredContent": {"private": TOKEN}}
        with self.assertRaises(grz.ToolError) as caught:
            await self.client.call("global__v2")
        self.assertEqual(self.server.call_result, caught.exception.result)
        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertEqual(self.server.call_result, await self.client.call("global__v2", check=False))
    async def test_jsonrpc_error_is_bounded_and_not_retried(self):
        self.server.call_behavior = lambda h, r: h.respond(json.dumps({"jsonrpc": "2.0", "id": r["id"], "error": {"code": -32603, "message": TOKEN}}).encode())
        with self.assertRaises(grz.ProtocolError) as caught:
            await self.client.call("global__v2")
        self.assertEqual(-32603, caught.exception.code)
        self.assertTrue(caught.exception.outcome_unknown)
        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertEqual(1, len(self.server.calls()))
    async def test_disconnect_after_effect_never_repeats_call(self):
        effects = []
        def disconnect(handler, request):
            effects.append(request)
            handler.connection.shutdown(socket.SHUT_RDWR)
            handler.connection.close()
        self.server.call_behavior = disconnect
        with self.assertRaises(grz.TransportError) as caught:
            await self.client.call("global__v2")
        self.assertTrue(caught.exception.outcome_unknown)
        self.assertEqual(1, len(effects))
    async def test_redirect_is_not_followed_and_failure_body_not_printed(self):
        self.server.call_behavior = lambda h, r: h.respond(TOKEN.encode(), 307, extra={"Location": self.server.url + "/stolen"})
        with self.assertRaises(grz.HttpError) as caught:
            await self.client.call("global__v2")
        self.assertEqual(307, caught.exception.status)
        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertFalse(any(path.endswith("stolen") for _, _, path in self.server.requests))
    async def test_unauthorized_does_not_refresh_or_use_cookies(self):
        self.server.call_behavior = lambda h, r: h.respond(TOKEN.encode(), 401)
        with self.assertRaises(grz.HttpError) as caught:
            await self.client.call("global__v2")
        self.assertEqual(401, caught.exception.status)
        self.assertFalse(caught.exception.outcome_unknown)
        self.assertEqual(1, len(self.server.calls()))
    async def test_invalid_json_wrong_id_and_oversized_response_fail_closed(self):
        await self.client.initialize(); await self.client.list_tools()
        for body in (b'{"invalid":', json.dumps({"jsonrpc": "2.0", "id": 999, "result": {}}).encode(), b'x'*500):
            self.server.call_behavior = lambda h, r, b=body: h.respond(b)
            self.client._max_bytes = 256
            with self.assertRaises(grz.ProtocolError) as caught:
                await self.client.call("global__v2")
            self.assertTrue(caught.exception.outcome_unknown)
        self.assertEqual(3, len(self.server.calls()))
    async def test_sse_response_preserves_data_and_ignores_notifications(self):
        def sse(h, r):
            body = ': comment\r\ndata: {"jsonrpc":"2.0","method":"notifications/progress"}\r\n\r\n'
            body += 'event: message\ndata: ' + json.dumps({"jsonrpc": "2.0", "id": r["id"], "result": RAW}) + '\n\n'
            h.respond(body.encode(), media="text/event-stream")
        self.server.call_behavior = sse
        self.assertEqual(RAW, await self.client.call("global__v2"))
        self.server.call_behavior = lambda h, r: h.respond(b'data: []\n\n', media="text/event-stream")
        with self.assertRaises(grz.ProtocolError) as caught:
            await self.client.call("global__v2")
        self.assertTrue(caught.exception.outcome_unknown)
    async def test_unsupported_version_stops_before_discovery(self):
        self.server.version = "2099-01-01"
        with self.assertRaises(grz.ProtocolError):
            await self.client.list_tools()
        self.assertEqual(["initialize"], [r["method"] for r, _, _ in self.server.requests])
    async def test_context_env_and_invalid_configuration_are_content_free(self):
        with mock.patch.dict(os.environ, {"GRZ_MCP_URL": self.server.url, "GRZ_MCP_TOKEN": TOKEN, "GRZ_MCP_CONTEXT": '{"workspace_mount_id":"env-mount"}'}):
            async with grz.Client.from_env(context={}) as client:
                await client.call("workspace__v3")
            self.assertNotIn("_grz", self.server.calls()[-1]["params"]["arguments"])
            async with grz.Client.from_env() as client:
                await client.call("workspace__v3")
            self.assertEqual({"workspace_mount_id": "env-mount"}, self.server.calls()[-1]["params"]["arguments"]["_grz"])
        for url in ("http://example.test/mcp", "https://user:"+TOKEN+"@example.test/mcp", "https://example.test/mcp?token="+TOKEN, "not a url"):
            with self.assertRaises(grz.ConfigurationError) as caught:
                grz.Client(url, TOKEN)
            self.assertNotIn(TOKEN, str(caught.exception))
        for token in ("", "a\r\nb", "a b"):
            with self.assertRaises(grz.ConfigurationError):
                grz.Client(self.server.url, token)
        self.assertNotIn(TOKEN, repr(self.client))
    async def test_non_json_arguments_and_closed_client_do_not_invoke(self):
        for args in ([], {"text": float("nan")}, {"text": "\ud800"}):
            with self.assertRaises(grz.ConfigurationError):
                await self.client.call("global__v2", args)
        await self.client.close()
        with self.assertRaises(grz.ConfigurationError):
            await self.client.call("global__v2")
        self.assertEqual([], self.server.calls())
    async def test_ca_env_supplements_system_and_bundled_trust(self):
        values = {"GRZ_MCP_URL": self.server.url, "GRZ_MCP_TOKEN": TOKEN, "GRZ_MCP_CA_BUNDLE": "corporate-ca.pem"}
        with mock.patch.dict(os.environ, values), mock.patch.object(grz.ssl, "create_default_context") as create:
            client = grz.Client.from_env(context={})
            self.assertIs(create.return_value, client._ssl_context())
            client._ssl_context()
            self.assertEqual(2, create.call_count)
            self.assertEqual([mock.call(cafile=grz.certifi.where()), mock.call(cafile="corporate-ca.pem")] * 2,
                             create.return_value.load_verify_locations.call_args_list)
            create.reset_mock()
            grz.Client.from_env(context={}, ca_bundle=None)._ssl_context()
            create.return_value.load_verify_locations.assert_called_once_with(cafile=grz.certifi.where())
    async def test_invalid_ca_configuration_never_disables_tls_or_exposes_details(self):
        for path in (False, 17, "", " ", "a\x00b"):
            with self.assertRaises(grz.ConfigurationError):
                grz.Client("https://server.example/mcp", TOKEN, ca_bundle=path)
        with mock.patch.object(grz.ssl, "create_default_context") as create:
            create.return_value.load_verify_locations.side_effect = OSError(TOKEN)
            client = grz.Client("https://server.example/mcp", TOKEN, ca_bundle="corporate-ca.pem")
            with self.assertRaises(grz.ConfigurationError) as caught:
                await client.initialize()
            self.assertNotIn(TOKEN, str(caught.exception))
            self.assertFalse(caught.exception.outcome_unknown)
        self.assertEqual([], self.server.calls())
    async def test_missing_ca_env_keeps_default_tls_trust(self):
        with mock.patch.dict(os.environ, {"GRZ_MCP_URL": self.server.url, "GRZ_MCP_TOKEN": TOKEN,
                                         "GRZ_MCP_CA_BUNDLE": ""}), mock.patch.object(grz.ssl, "create_default_context") as create:
            client = grz.Client.from_env(context={})
            self.assertIs(create.return_value, client._ssl_context())
            create.return_value.load_verify_locations.assert_called_once_with(cafile=grz.certifi.where())
    async def test_parallel_calls_overlap_with_one_initialization_and_catalog(self):
        barrier = threading.Barrier(2, timeout=5)
        def call(handler, request):
            barrier.wait()
            result = {"content": [], "structuredContent": request["params"]["arguments"]}
            handler.respond(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}).encode())
        self.server.call_behavior = call
        first, second = await asyncio.wait_for(asyncio.gather(
            self.client.call("global__v2", {"value": 1}),
            self.client.call("global__v2", {"value": 2}),
        ), timeout=10)
        self.assertEqual(1, first["structuredContent"]["value"])
        self.assertEqual(2, second["structuredContent"]["value"])
        methods = [r["method"] for r, _, _ in self.server.requests]
        self.assertEqual(1, methods.count("initialize"))
        self.assertEqual(1, methods.count("notifications/initialized"))
        self.assertEqual(1, methods.count("tools/list"))
        ids = [r["id"] for r, _, _ in self.server.requests if "id" in r]
        self.assertEqual(len(ids), len(set(ids)))

    async def test_cancelling_one_call_does_not_retry_it_or_close_shared_client(self):
        entered, release = threading.Event(), threading.Event()
        self.addCleanup(release.set)
        def call(handler, request):
            if request["params"]["arguments"].get("slow"):
                entered.set()
                release.wait(10)
                return
            handler.respond(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": RAW}).encode())
        self.server.call_behavior = call
        slow = asyncio.create_task(self.client.call("global__v2", {"slow": True}))
        try:
            self.assertTrue(await asyncio.to_thread(entered.wait, 5))
            slow.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await slow
            self.assertEqual(RAW, await self.client.call("global__v2"))
            self.assertEqual(2, len(self.server.calls()))
        finally:
            release.set()
            slow.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await slow

    async def test_response_cookies_are_never_forwarded(self):
        def call(handler, request):
            handler.respond(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": RAW}).encode(),
                            extra={"Set-Cookie": "session=" + TOKEN + "; Path=/"})
        self.server.call_behavior = call
        await self.client.call("global__v2")
        await self.client.call("global__v2")
        self.assertTrue(all("Cookie" not in headers for _, headers, _ in self.server.requests))

    async def test_sse_returns_response_before_server_closes_stream(self):
        release = threading.Event()
        self.addCleanup(release.set)
        def call(handler, request):
            handler.send_response(200)
            handler.send_header("Content-Type", "text/event-stream")
            handler.end_headers()
            body = 'data: ' + json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": RAW}) + '\n\n'
            handler.wfile.write(body.encode())
            handler.wfile.flush()
            release.wait(10)
        self.server.call_behavior = call
        try:
            self.assertEqual(RAW, await asyncio.wait_for(self.client.call("global__v2"), 5))
            self.assertFalse(release.is_set())
        finally:
            release.set()

    async def test_only_async_client_api_is_exposed(self):
        import inspect
        for name in ("initialize", "list_tools", "describe_tool", "call", "close"):
            self.assertTrue(inspect.iscoroutinefunction(getattr(grz.Client, name)))
        self.assertFalse(hasattr(grz.Client, "__enter__"))
        self.assertFalse(hasattr(grz.Client, "__exit__"))

    def env(self):
        return {**os.environ, "GRZ_MCP_URL": self.server.url, "GRZ_MCP_TOKEN": TOKEN,
                "GRZ_MCP_CONTEXT": "{}", "GRZ_PYTHON": sys.executable, "PYTHONIOENCODING": "utf-8"}
    async def test_python_import_from_file_and_inline(self):
        code = ("import asyncio, grz, json\n"
                "async def main():\n"
                "    async with grz.Client.from_env() as c:\n"
                "        print(json.dumps(await c.call('global__v2')))\n"
                "asyncio.run(main())\n")
        env = {**self.env(), "PYTHONPATH": os.environ["GRZ_SDK_HOME"]}
        with tempfile.TemporaryDirectory() as root:
            path = pathlib.Path(root)/"script with spaces.py"; path.write_text(code)
            for args, stdin in (([sys.executable, str(path)], None), ([sys.executable, "-"], code)):
                result = subprocess.run(args, input=stdin, env=env, cwd=root, capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(RAW, json.loads(result.stdout))
    @unittest.skipIf(os.name == "nt", "POSIX source wrapper; Python transport is tested on Windows")
    async def test_sh_and_bash_literal_arguments_stdin_and_error_exit(self):
        payload = {"text": "  $(touch SHOULD_NOT_EXIST); '\" %s\nשלום 😀  "}
        import shutil
        shells = ["/bin/sh"] + ([shutil.which("bash")] if shutil.which("bash") else [])
        with tempfile.TemporaryDirectory() as root:
            for shell in shells:
                for stdin_mode in (False, True):
                    command = '. "$GRZ_SDK_HOME/grz.sh"\ngrz_call workspace__v3'
                    if not stdin_mode:
                        command += " " + shlex.quote(json.dumps(payload, ensure_ascii=False))
                    result = subprocess.run([shell, "-c", command], input=json.dumps(payload) if stdin_mode else None,
                                            env=self.env(), cwd=root, capture_output=True, text=True, timeout=10)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(RAW, json.loads(result.stdout))
                    self.assertEqual(payload, self.server.calls()[-1]["params"]["arguments"])
                    self.assertFalse((pathlib.Path(root)/"SHOULD_NOT_EXIST").exists())
            self.server.call_result = {"content": [{"type": "text", "text": "expected-error"}], "isError": True}
            result = subprocess.run(["/bin/sh", "-c", '. "$GRZ_SDK_HOME/grz.sh"; grz_call global__v2 \'{}\''],
                                    env=self.env(), capture_output=True, text=True, timeout=10)
            self.assertEqual(2, result.returncode)
            self.assertEqual(self.server.call_result, json.loads(result.stdout))
            self.assertIn("isError=true", result.stderr)
            self.assertNotIn(TOKEN, result.stderr)
    async def test_cli_malformed_input_is_failure_without_tool_call(self):
        result = subprocess.run([sys.executable, str(pathlib.Path(os.environ["GRZ_SDK_HOME"])/"grz.py"), "call", "global__v2"],
                                input="{bad", env=self.env(), capture_output=True, text=True, timeout=10)
        self.assertEqual(1, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual([], self.server.calls())


if __name__ == "__main__":
    unittest.main(verbosity=2)
