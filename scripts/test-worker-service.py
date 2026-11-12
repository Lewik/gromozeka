#!/usr/bin/env python3
"""Exercise macOS Worker LaunchAgent generation without touching a live service."""

import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import json
import shlex


SERVICE_SCRIPT = Path(__file__).resolve().parents[1] / "deploy/distribution/gromozeka-worker-service"


class WorkerServiceTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform == "darwin", "Uses the macOS archive tool")
    def test_update_directory_swap_and_launch_failure_recovery(self):
        source = SERVICE_SCRIPT.parents[2] / "application-updates/src/jvmMain/resources/updates/install.sh"
        for fail in (False, True):
            with self.subTest(launch_failure=fail), tempfile.TemporaryDirectory(prefix="gromozeka-update-fixture-") as temporary:
                root = Path(temporary)
                destination = root / "installed/Gromozeka.app"
                package = root / "package/Gromozeka.app"
                destination.mkdir(parents=True)
                (destination / "old.txt").write_text("old")
                (package / "Contents/MacOS").mkdir(parents=True)
                binary = package / "Contents/MacOS/Gromozeka"
                binary.write_text("#!/bin/sh\nexit 0\n")
                binary.chmod(0o755)
                (package / "new.txt").write_text("new")
                archive = root / "app.zip"
                subprocess.run(["ditto", "-c", "-k", "--keepParent", str(package), str(archive)], check=True)
                noop = root / "native-noop"
                noop.write_text("#!/bin/sh\nexit 0\n")
                noop.chmod(0o755)
                launcher = root / "native-launch"
                launcher.write_text('#!/bin/sh\n' + ('test ! -f "$1/new.txt"\n' if fail else 'test -f "$1/new.txt"\n'))
                launcher.chmod(0o755)
                script = root / "install.sh"
                script.write_text(source.read_text().replace("/bin/launchctl", shlex.quote(str(noop)))
                                  .replace("/usr/bin/codesign", shlex.quote(str(noop)))
                                  .replace("/usr/bin/open", shlex.quote(str(launcher))))
                old = subprocess.Popen(["/usr/bin/true"])
                old.wait()
                result = root / "result.json"
                process = subprocess.run(["bash", str(script), str(archive), str(destination), "CLIENT", "fixture",
                                          str(old.pid), str(result), "isolated-fixture", str(os.getuid())], capture_output=True, timeout=20, env={**os.environ, "HOME": str(root / "home")})
                self.assertEqual(fail, process.returncode != 0, process.stderr.decode())
                self.assertEqual(fail, (destination / "old.txt").exists())
                self.assertEqual(not fail, (destination / "new.txt").exists())
                if fail:
                    self.assertEqual("FAILED", json.loads(result.read_text())["phase"])
                else:
                    self.assertFalse(result.exists())
                    self.assertTrue(Path(str(destination) + ".previous-fixture/old.txt").exists())

    @unittest.skipUnless(sys.platform == "darwin", "Uses the macOS archive tool")
    def test_abrupt_updater_death_recovers_from_each_replacement_boundary(self):
        source = SERVICE_SCRIPT.parents[2] / "application-updates/src/jvmMain/resources/updates/install.sh"
        for boundary in ("old-moved", "new-moved", "installed", "rollback-moved"):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(prefix="grz-update-crash-") as temporary:
                root = Path(temporary)
                home = root / "home"
                recovery = home / "Library/LaunchAgents/isolated-crash.plist"
                recovery.parent.mkdir(parents=True)
                recovery.write_text("registered recovery job")
                destination = root / "installed/Gromozeka.app"
                destination.mkdir(parents=True)
                (destination / "old.txt").write_text("old")
                package = root / "package/Gromozeka.app"
                (package / "Contents/MacOS").mkdir(parents=True)
                binary = package / "Contents/MacOS/Gromozeka"
                binary.write_text("#!/bin/sh\nexit 0\n")
                binary.chmod(0o755)
                (package / "new.txt").write_text("new")
                archive = root / "app.zip"
                subprocess.run(["ditto", "-c", "-k", "--keepParent", str(package), str(archive)], check=True)
                noop = root / "noop"
                noop.write_text("#!/bin/sh\nexit 0\n")
                noop.chmod(0o755)
                launcher = root / "launch"
                launcher.write_text('#!/bin/sh\n' + ('test ! -f "$1/new.txt"\n' if boundary == "rollback-moved" else 'exit 0\n'))
                launcher.chmod(0o755)
                script = root / "install.sh"
                script.write_text(source.read_text().replace("/bin/launchctl", shlex.quote(str(noop)))
                                  .replace("/usr/bin/codesign", shlex.quote(str(noop)))
                                  .replace("/usr/bin/open", shlex.quote(str(launcher))))
                tools = root / "tools"
                tools.mkdir()
                mover = tools / "mv"
                mover.write_text(f"#!{sys.executable}\n" + r'''import os, pathlib, signal, subprocess, sys
args = sys.argv[1:]
source, target = args[-2:]
root = pathlib.Path(os.environ["FIXTURE_ROOT"])
boundary = os.environ["FIXTURE_BOUNDARY"]
phase = pathlib.Path(source).read_text().strip() if source.endswith("transaction-crash.new") else ""
subprocess.run(["/bin/mv", *args], check=True)
kill = (boundary == "old-moved" and target.endswith(".previous-crash") or
        boundary == "new-moved" and source.endswith(".update-crash/Gromozeka.app") or
        boundary == "installed" and phase == "installed" or
        boundary == "rollback-moved" and source.endswith(".previous-crash"))
marker = root / "killed"
if kill and not marker.exists():
    marker.touch()
    os.kill(os.getppid(), signal.SIGKILL)
''')
                mover.chmod(0o755)
                old = subprocess.Popen(["/usr/bin/true"])
                old.wait()
                result = root / "result.json"
                args = ["bash", str(script), str(archive), str(destination), "CLIENT", "crash",
                        str(old.pid), str(result), "isolated-crash", str(os.getuid())]
                env = {**os.environ, "HOME": str(home), "PATH": str(tools) + os.pathsep + os.environ["PATH"],
                       "FIXTURE_ROOT": str(root), "FIXTURE_BOUNDARY": boundary}
                crashed = subprocess.run(args, env=env, capture_output=True, timeout=30)
                self.assertEqual(-9, crashed.returncode, crashed.stderr.decode())
                self.assertTrue(recovery.exists(), "Crash must retain an entry point outside the missing installation")
                if boundary == "old-moved":
                    self.assertFalse(destination.exists())
                recovered = subprocess.run(args, env=env, capture_output=True, timeout=30)
                self.assertEqual(0, recovered.returncode, recovered.stderr.decode())
                self.assertEqual(boundary != "installed", (destination / "old.txt").exists())
                self.assertEqual(boundary == "installed", (destination / "new.txt").exists())
                self.assertFalse(recovery.exists())
                if boundary != "installed":
                    self.assertEqual("FAILED", json.loads(result.read_text())["phase"])
                else:
                    self.assertFalse(result.exists())

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="gromozeka-worker-service-test-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.home = root / "home"
        package = root / "package"
        tools = root / "tools"
        for directory in (self.home, package / "bin", package / "app/native", tools):
            directory.mkdir(parents=True, exist_ok=True)
        service = package / "bin/gromozeka-worker-service"
        shutil.copy2(SERVICE_SCRIPT, service)

        def executable(path, text):
            path.write_text(text)
            path.chmod(0o755)

        # A fake package and tools keep this test independent of macOS signing,
        # native compilation, and launchd. The real generator is still executed.
        executable(package / "app/native/gromozeka-worker-launcher", "#!/bin/sh\nexit 99\n")
        executable(package / "bin/gromozeka-worker", "#!/bin/sh\nexit 99\n")
        executable(tools / "uname", "#!/bin/sh\nprintf 'Darwin\\n'\n")
        executable(tools / "codesign", "#!/bin/sh\nexit 0\n")
        self.launchctl_called = root / "launchctl-called"
        executable(
            tools / "launchctl",
            '#!/bin/sh\nprintf "unexpected launchctl invocation\\n" > "$TEST_LAUNCHCTL_CALLED"\nexit 99\n',
        )
        executable(
            tools / "plutil",
            f"#!{sys.executable}\nimport plistlib, sys\n"
            "with open(sys.argv[-1], 'rb') as stream:\n    plistlib.load(stream)\n",
        )
        env = {key: value for key, value in os.environ.items() if not key.startswith("GROMOZEKA_")}
        env.update(
            HOME=str(self.home),
            PATH=str(tools) + os.pathsep + os.environ.get("PATH", "/usr/bin:/bin"),
            GROMOZEKA_WORKER_SERVICE_HOME=str(self.home / "launcher"),
            GROMOZEKA_WORKER_SERVICE_DRY_RUN="true",
            TEST_LAUNCHCTL_CALLED=str(self.launchctl_called),
            PYTHONDONTWRITEBYTECODE="1",
        )
        result = subprocess.run(
            ["bash", str(service), "install"], env=env, capture_output=True, check=True, timeout=20,
        )
        self.agent = plistlib.loads(result.stdout)
        launcher_info = self.home / "launcher/Gromozeka Worker.app/Contents/Info.plist"
        self.launcher = plistlib.loads(launcher_info.read_bytes())

    def test_user_requested_work_uses_application_resource_policy(self):
        self.assertEqual("Interactive", self.agent["ProcessType"])
        self.assertFalse(self.agent.get("LowPriorityIO", False))
        self.assertEqual(0, self.agent.get("Nice", 0))

    def test_launcher_remains_headless_with_stable_permission_identity(self):
        self.assertIs(self.launcher["LSBackgroundOnly"], True)
        self.assertEqual("com.gromozeka.worker.launcher", self.launcher["CFBundleIdentifier"])
        self.assertIs(self.agent["RunAtLoad"], True)
        self.assertIs(self.agent["KeepAlive"], True)

    def test_dry_run_never_loads_service_or_installs_launch_agent(self):
        self.assertFalse(self.launchctl_called.exists())
        self.assertFalse((self.home / "Library/LaunchAgents/com.gromozeka.worker.plist").exists())


if __name__ == "__main__":
    unittest.main()
