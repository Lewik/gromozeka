#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("ios_client_runner", Path(__file__).with_name("run-ios-client-tests.py"))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
OWNED = "00000000-0000-0000-0000-000000000002"
EXISTING = "00000000-0000-0000-0000-000000000001"
RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-26-3"


def catalog():
    return {
        "devicetypes": [{"identifier": "phone", "productFamily": "iPhone"}],
        "runtimes": [{"identifier": RUNTIME, "version": "26.3", "isAvailable": True}],
        "devices": {RUNTIME: [{"udid": EXISTING, "deviceTypeIdentifier": "phone", "isAvailable": True, "state": "Booted"}]},
    }


class FakeSimulator:
    def __init__(self, fail_boot=False):
        self.calls = []
        self.state = "Shutdown"
        self.fail_boot = fail_boot

    def __call__(self, *arguments, capture=False):
        self.calls.append(arguments)
        if arguments == ("list", "--json"):
            return json.dumps(catalog())
        if arguments == ("list", "devices", "--json"):
            data = catalog()
            data["devices"][RUNTIME].append({"udid": OWNED, "state": self.state})
            return json.dumps(data)
        if arguments[0] == "create":
            return OWNED
        if arguments[0] == "boot":
            if self.fail_boot:
                raise RuntimeError("synthetic boot failure")
            self.state = "Booted"
        return ""


class IosClientRunnerTest(unittest.TestCase):
    def test_selection_uses_an_available_compatible_template(self):
        self.assertEqual((RUNTIME, "phone"), RUNNER.select_template(catalog()))
        empty = catalog()
        empty["devices"] = {}
        with self.assertRaises(RuntimeError):
            RUNNER.select_template(empty)

    def test_success_and_test_failure_both_delete_only_the_owned_device(self):
        for status in (0, 7):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                simulator = FakeSimulator()
                def execute(command, cwd):
                    self.assertEqual(Path(directory), cwd)
                    self.assertIn(":presentation:iosSimulatorArm64Test", command)
                    self.assertEqual(OWNED, command[command.index("--device") + 1])
                    init = Path(command[command.index("--init-script") + 1])
                    self.assertIn("standalone.set(false)", init.read_text())
                    self.assertIn("-Pgromozeka.version=6.0.3", command)
                    return SimpleNamespace(returncode=status)
                result = RUNNER.run_tests(["-Pgromozeka.version=6.0.3"], simulator, execute, Path(directory))
                self.assertEqual(status, result)
                self.assertEqual(("shutdown", OWNED), simulator.calls[-2])
                self.assertEqual(("delete", OWNED), simulator.calls[-1])
                self.assertFalse(any(EXISTING in call for call in simulator.calls))
                self.assertEqual([], list((Path(directory) / "build/ios-platform-tests").glob("*.json")))

    def test_boot_failure_still_deletes_the_created_device(self):
        with tempfile.TemporaryDirectory() as directory:
            simulator = FakeSimulator(fail_boot=True)
            with self.assertRaisesRegex(RuntimeError, "synthetic boot failure"):
                RUNNER.run_tests([], simulator, lambda *_: self.fail("Gradle must not start"), Path(directory))
            self.assertEqual(("delete", OWNED), simulator.calls[-1])
            self.assertNotIn(("shutdown", EXISTING), simulator.calls)

    def test_caller_cannot_redirect_the_test_to_an_unowned_device(self):
        simulator = FakeSimulator()
        for arguments in (["--device", EXISTING], ["--device=" + EXISTING]):
            with self.assertRaises(ValueError):
                RUNNER.run_tests(arguments, simulator)
        self.assertEqual([], simulator.calls)

    def test_unknown_cleanup_state_is_not_reported_as_success(self):
        def unavailable(*arguments, capture=False):
            return json.dumps({"devices": {RUNTIME: [{"udid": EXISTING, "state": "Booted"}]}})
        with self.assertRaises(RuntimeError):
            RUNNER.remove_owned_device(OWNED, unavailable)


if __name__ == "__main__":
    unittest.main()
