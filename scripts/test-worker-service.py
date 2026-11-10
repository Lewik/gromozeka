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
                                          str(old.pid), str(result), "isolated-fixture", str(os.getuid())], capture_output=True, timeout=20)
                self.assertEqual(fail, process.returncode != 0, process.stderr.decode())
                self.assertEqual(fail, (destination / "old.txt").exists())
                self.assertEqual(not fail, (destination / "new.txt").exists())
                if fail:
                    self.assertEqual("FAILED", json.loads(result.read_text())["phase"])
                else:
                    self.assertFalse(result.exists())
                    self.assertTrue(Path(str(destination) + ".previous-fixture/old.txt").exists())

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
