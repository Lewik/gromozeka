"""Offline managed-runner regressions; no model, remote MCP or user files."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SDK = Path(os.environ["GRZ_SDK_HOME"])


class RunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="grz-runner-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.cwd = self.root / "workspace with spaces"
        self.cwd.mkdir()
        self.sequence = 0

    def run_script(self, code, *, inline=True, arguments=None, source_directory=None, environment=None):
        self.sequence += 1
        directory = source_directory or self.root
        directory.mkdir(parents=True, exist_ok=True)
        source = directory / ("script %value% & שלום " + str(self.sequence) + ".py")
        source.write_text(code, encoding="utf-8")
        descriptor = self.root / (str(self.sequence) + ".json")
        descriptor.write_text(json.dumps({"source_path": str(source), "arguments": arguments or [], "inline": inline}), encoding="utf-8")
        env = {**os.environ, "GRZ_MCP_URL": "http://127.0.0.1:1/mcp", "GRZ_MCP_TOKEN": "synthetic-runner-token",
               "GRZ_MCP_CA_BUNDLE": "", "GRZ_MCP_CONTEXT": "{}", **(environment or {})}
        result = subprocess.run([sys.executable, "-I", "-B", "-u", "-X", "utf8", str(SDK / "grz_runner.py"), str(descriptor)],
                                cwd=self.cwd, env=env, capture_output=True, text=True, encoding="utf-8", timeout=20)
        return result, source

    def test_inline_top_level_await_arguments_cwd_and_imports(self):
        (self.cwd / "local_helper.py").write_text("VALUE = 21\n", encoding="utf-8")
        arguments = ["", "a b", "$(touch forbidden)", "%PATH%!&", "שלום 😀", "line\nbreak"]
        code = """import asyncio, json, os, sys, local_helper
async def value():
    await asyncio.sleep(0.01)
    return local_helper.VALUE
values = await asyncio.gather(value(), value())
print(json.dumps({"value": sum(values), "arguments": sys.argv[1:], "cwd": os.getcwd(), "file": __file__}))
"""
        result, source = self.run_script(code, arguments=arguments)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"value": 42, "arguments": arguments, "cwd": str(self.cwd), "file": str(source)}, json.loads(result.stdout))
        self.assertFalse((self.cwd / "forbidden").exists())

    def test_file_imports_its_siblings_and_has_real_main_module(self):
        folder = self.root / "program"
        folder.mkdir()
        (folder / "sibling.py").write_text("VALUE = 42\n", encoding="utf-8")
        code = """from __future__ import annotations
from dataclasses import dataclass
import __main__, sibling
@dataclass
class Value:
    number: int
assert __main__.Value is Value
print(Value(sibling.VALUE).number)
"""
        result, _ = self.run_script(code, inline=False, source_directory=folder)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("42\n", result.stdout)

    def test_ordinary_script_with_asyncio_run(self):
        code = """import asyncio
async def main():
    await asyncio.sleep(0)
    print("ordinary-main")
if __name__ == "__main__":
    asyncio.run(main())
"""
        result, _ = self.run_script(code, inline=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("ordinary-main\n", result.stdout)

    def test_source_location_and_partial_effects_survive_failure(self):
        result, source = self.run_script('from pathlib import Path\nPath("effect.txt").write_text("done")\nprint("before failure")\nraise RuntimeError("expected failure")\n')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("before failure\n", result.stdout)
        self.assertIn(str(source), result.stderr)
        self.assertIn("line 4", result.stderr)
        self.assertIn("expected failure", result.stderr)
        self.assertEqual("done", (self.cwd / "effect.txt").read_text())

    def test_syntax_error_is_a_failure(self):
        result, source = self.run_script('value = 1\nvalue += 2\nif :\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn(str(source), result.stderr)
        self.assertIn("line 3", result.stderr)
        self.assertIn("SyntaxError", result.stderr)

    def test_workspace_and_ambient_paths_do_not_shadow_sdk(self):
        for name in ("grz.py", "httpx.py"):
            (self.cwd / name).write_text('raise RuntimeError("workspace shadow executed")\n')
        poison = self.root / "poison"
        poison.mkdir()
        (poison / "grz.py").write_text('raise RuntimeError("ambient shadow executed")\n')
        code = 'import grz, httpx\nprint(grz.Client.__name__)\nprint(httpx.__version__)\n'
        result, _ = self.run_script(code, environment={"PYTHONPATH": str(poison)})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("Client\n0.28.1\n", result.stdout)

    def test_process_state_is_not_reused(self):
        first, _ = self.run_script('import os\nstate = 123\nos.environ["GRZ_TEST_STATE"] = "changed"\n')
        self.assertEqual(0, first.returncode, first.stderr)
        second, _ = self.run_script('import os\nassert "state" not in globals()\nassert "GRZ_TEST_STATE" not in os.environ\n')
        self.assertEqual(0, second.returncode, second.stderr)

    def test_invalid_descriptor_is_not_silently_repaired(self):
        descriptor = self.root / "invalid.json"
        descriptor.write_text('{"source_path":"missing.py","arguments":[],"inline":false,"unexpected":1}')
        result = subprocess.run([sys.executable, "-I", "-B", str(SDK / "grz_runner.py"), str(descriptor)],
                                cwd=self.cwd, capture_output=True, text=True, timeout=20)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Invalid Python launch descriptor", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
