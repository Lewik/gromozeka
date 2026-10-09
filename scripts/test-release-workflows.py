#!/usr/bin/env python3
"""Offline release safety and artifact-graph contracts (no GitHub/AWS mutations)."""

import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"


def load_yaml(path):
    # BaseLoader preserves `on` and boolean input defaults as literal strings.
    return yaml.load(path.read_text(), Loader=yaml.BaseLoader)


RELEASE = load_yaml(WORKFLOWS / "release.yml")
JOBS = RELEASE["jobs"]


def needs(job):
    value = JOBS[job].get("needs", [])
    return {value} if isinstance(value, str) else set(value)


def scripts(job):
    return "\n".join(step.get("run", "") for step in job.get("steps", []))


def artifact_options(job, action):
    return [step["with"] for step in JOBS[job].get("steps", [])
            if step.get("uses", "").startswith(f"actions/{action}-artifact@")]


class WorkflowContracts(unittest.TestCase):
    def test_only_manual_or_reusable_entrypoints(self):
        expected = {
            "release.yml": {"workflow_dispatch"},
            "deploy-release.yml": {"workflow_dispatch", "workflow_call"},
            "windows-worker-service.yml": {"workflow_call"},
            "build-jni-libraries.yml": {"workflow_dispatch"},
        }
        self.assertEqual(set(expected), {p.name for p in WORKFLOWS.glob("*.y*ml")})
        for filename, events in expected.items():
            with self.subTest(workflow=filename):
                self.assertEqual(events, set(load_yaml(WORKFLOWS / filename)["on"]))

    def test_safe_dispatch_defaults(self):
        inputs = RELEASE["on"]["workflow_dispatch"]["inputs"]
        for name, default in (("publish_release", "false"), ("deploy_aws", "false"), ("skip_android", "false"), ("skip_ios", "true")):
            self.assertEqual(default, inputs[name]["default"])
            self.assertEqual("boolean", inputs[name]["type"])
        self.assertIn("!inputs.skip_ios", JOBS["verify-ios"]["if"])
        self.assertIn('"$GITHUB_REF" != "refs/heads/main"', scripts(JOBS["metadata"]))

    def test_android_skip_keeps_jvm_checks_and_gates_artifacts(self):
        steps = {step.get("name"): step for step in JOBS["verify-client"]["steps"]}
        self.assertNotIn("if", steps["Verify JVM client"])
        self.assertIn(":presentation:jvmTest", steps["Verify JVM client"]["run"])
        self.assertNotIn("android", steps["Verify JVM client"]["run"])
        for name in ("Verify Android clients", "Upload Android APKs"):
            self.assertIn("!inputs.skip_android", steps[name]["if"])
        release = {step.get("name"): step for step in JOBS["release"]["steps"]}
        self.assertIn("!inputs.skip_android", release["Download Android APKs"]["if"])
        prepare = release["Prepare release assets"]
        self.assertEqual("${{ inputs.skip_android }}", prepare["env"]["SKIP_ANDROID"])
        self.assertIn('if [[ "$SKIP_ANDROID" != "true" ]]', prepare["run"])
        self.assertIn('sha256sum "${assets[@]}"', prepare["run"])

    def test_release_notes_match_android_selection(self):
        step = next(step for step in JOBS["release"]["steps"] if step.get("name") == "Write release notes")
        for skip in ("true", "false"):
            with self.subTest(skip_android=skip), tempfile.TemporaryDirectory() as directory:
                subprocess.run(["bash", "-eu", "-c", step["run"]], cwd=directory,
                               env={**os.environ, "SKIP_ANDROID": skip}, check=True)
                notes = (Path(directory) / "release-notes.md").read_text()
                self.assertEqual(skip == "false", "gromozeka-client-android-debug.apk" in notes)
                self.assertEqual(skip == "false", "debug signing key" in notes)
                self.assertIn("gromozeka-client-macos-arm64.dmg", notes)
                self.assertIn("gromozeka-server-linux-x64.tar.gz", notes)

    def test_prepared_assets_obey_android_selection(self):
        step = next(step for step in JOBS["release"]["steps"] if step.get("name") == "Prepare release assets")
        script = step["run"].replace("${{ needs.metadata.outputs.version }}", "4.9.0")
        self.assertNotIn("${{", script)
        base = {"gromozeka-client-macos-arm64.dmg", "gromozeka-client-windows-x64.zip",
                "gromozeka-browser-bridge.zip"}
        for component in ("server", "worker"):
            base.update(f"gromozeka-{component}-{suffix}" for suffix in
                        ("macos-arm64.tar.gz", "windows-x64.zip", "linux-x64.tar.gz"))
        for skip in ("true", "false"):
            with self.subTest(skip_android=skip), tempfile.TemporaryDirectory() as directory:
                work = Path(directory)
                (work / "downloaded-artifacts").mkdir()
                for name in base:
                    (work / "downloaded-artifacts" / name).write_text("synthetic artifact")
                distribution = work / "deploy/distribution"
                distribution.mkdir(parents=True)
                for name in ("compose.yaml", "Caddyfile", "Caddyfile.internal", "Caddyfile.provided",
                             "SERVER_README.md", "gromozeka.env.example"):
                    shutil.copyfile(ROOT / "deploy/distribution" / name, distribution / name)
                shutil.copyfile(ROOT / "LICENSE", work / "LICENSE")
                if skip == "false":
                    for module in ("presentation-android", "mobile-worker-android"):
                        apk = work / "android-apks" / module / "build/outputs/apk/debug" / f"{module}-debug.apk"
                        apk.parent.mkdir(parents=True)
                        apk.write_text("synthetic APK")
                # macOS has shasum rather than sha256sum; use a portable fixture implementation.
                binary = work / "bin"
                binary.mkdir()
                checksum = binary / "sha256sum"
                checksum.write_text(f"#!{sys.executable}\nimport hashlib,sys\nfrom pathlib import Path\n"
                                    "for name in sys.argv[1:]: print(hashlib.sha256(Path(name).read_bytes()).hexdigest()+'  '+name)\n")
                checksum.chmod(0o700)
                subprocess.run(["bash", "-c", script], cwd=work, check=True,
                               env={**os.environ, "SKIP_ANDROID": skip, "PATH": str(binary) + os.pathsep + os.environ["PATH"]})
                expected = base | {"gromozeka-server-stack.zip"}
                if skip == "false":
                    expected |= {"gromozeka-client-android-debug.apk", "gromozeka-worker-android-debug.apk"}
                checksums = (work / "release-assets/SHA256SUMS").read_text()
                self.assertEqual(expected, {line.split()[1] for line in checksums.splitlines()})
                self.assertEqual(expected | {"SHA256SUMS"}, {p.name for p in (work / "release-assets").iterdir()})

    def test_slots_are_in_the_release_verification_set(self):
        verification = scripts(JOBS["verify-runtime"])
        for check in ("*PostgresSlotRepositoryTest", "*PostgresWorkerRequestRepositoryTest", "*Slot*"):
            self.assertIn(check, verification)

    def test_graph_has_no_cycles_or_unknown_dependencies(self):
        def visit(job, path):
            self.assertIn(job, JOBS)
            self.assertNotIn(job, path, f"Dependency cycle: {path} -> {job}")
            for dependency in needs(job):
                visit(dependency, path + [job])
        for job in JOBS:
            visit(job, [])
            reusable = JOBS[job].get("uses", "")
            if reusable.startswith("./"):
                self.assertTrue((ROOT / reusable).is_file())

    def test_build_parallelism_and_complete_publication_gate(self):
        self.assertEqual({"metadata", "runtime-inputs", "verify-web"}, needs("server-image"))
        self.assertEqual({"metadata", "runtime-inputs", "verify-web", "worker-launcher-macos"}, needs("standalone"))
        self.assertEqual({"metadata"}, needs("runtime-inputs"))
        required = {"verify-runtime", "verify-windows-worker", "verify-client", "verify-web",
                    "verify-e2e", "verify-ios", "runtime-inputs", "standalone", "server-image",
                    "client-macos", "client-windows", "browser-bridge"}
        self.assertEqual(required, needs("verify"))
        for job in required:
            self.assertIn(f"needs.{job}.result == 'success'", JOBS["verify"]["if"])
        self.assertIn("inputs.skip_ios && needs.verify-ios.result == 'skipped'", JOBS["verify"]["if"])

    def test_shared_inputs_are_restored_not_rebuilt(self):
        uploads = {item["name"]: item for item in artifact_options("runtime-inputs", "upload")}
        self.assertEqual({"server/build/libs/gromozeka-server.jar", "worker/build/libs/gromozeka-worker.jar"},
                         set(uploads["runtime-jars"]["path"].splitlines()))
        self.assertEqual("build/release/browser-mcp-runtime.tar.gz", uploads["browser-mcp-runtime"]["path"])
        self.assertIn(":server:bootJar :worker:bootJar", scripts(JOBS["runtime-inputs"]))
        for job in ("standalone", "server-image"):
            downloads = {item["name"]: item for item in artifact_options(job, "download")}
            self.assertEqual(".", downloads["runtime-jars"]["path"])
            self.assertIn("server-web-assets", downloads)
            self.assertNotIn("gradlew", scripts(JOBS[job]))
        self.assertIn("tar -xzf build/release/browser-mcp-runtime.tar.gz", scripts(JOBS["standalone"]))

    def test_all_platform_packages_fan_in_without_collisions(self):
        matrix = JOBS["standalone"]["strategy"]["matrix"]["include"]
        self.assertEqual({("macos", "arm64"), ("linux", "x64"), ("windows", "x64")},
                         {(item["platform"], item["architecture"]) for item in matrix})
        for component in ("server", "worker"):
            self.assertIn(f"package-standalone.sh {component}", scripts(JOBS["standalone"]))
        self.assertEqual("standalone-packages-${{ matrix.platform }}-${{ matrix.architecture }}",
                         artifact_options("standalone", "upload")[0]["name"])
        download = next(item for item in artifact_options("release", "download")
                        if item.get("pattern") == "standalone-packages-*")
        self.assertEqual("true", download["merge-multiple"])

    def test_image_build_needs_no_publication_credentials(self):
        steps = JOBS["server-image"]["steps"]
        build = next(step["with"] for step in steps if step.get("uses", "").startswith("docker/build-push-action@"))
        self.assertEqual("false", build["push"])
        self.assertIn("type=oci,", build["outputs"])
        self.assertIn("copy --all --preserve-digests", scripts(JOBS["server-image"]))
        self.assertNotIn("secrets.", str(JOBS["server-image"]))
        self.assertFalse(any("login" in step.get("uses", "") or "configure-aws" in step.get("uses", "") for step in steps))
        self.assertNotIn("build-push-action", str(JOBS["images"]))
        self.assertIn("copy --all --preserve-digests", scripts(JOBS["images"]))
        self.assertIn("oci-archive:$PWD/build/release/server-image.tar", scripts(JOBS["images"]))

    def test_oci_tools_are_preinstalled_and_each_copy_is_bounded(self):
        for job in ("standalone", "server-image", "images"):
            self.assertEqual("ubuntu-24.04", JOBS[job]["runs-on"])
            self.assertNotIn("apt-get", scripts(JOBS[job]))
        self.assertIn("command -v brotli", scripts(JOBS["standalone"]))
        for job in ("server-image", "images"):
            self.assertIn("skopeo --version", scripts(JOBS[job]))
            for step in JOBS[job]["steps"]:
                if "skopeo" in step.get("run", ""):
                    self.assertIn("timeout-minutes", step)
                    self.assertLessEqual(int(step["timeout-minutes"]), 18)
                for line in step.get("run", "").splitlines():
                    if line.strip().startswith("skopeo") and "copy" in line:
                        self.assertRegex(line, r"skopeo --command-timeout [1-5]m copy --all --preserve-digests")

    def test_all_publication_operations_are_explicitly_gated(self):
        for job in ("reserve-release-tag", "images", "release"):
            self.assertIn("needs.metadata.outputs.publish == 'true'", JOBS[job]["if"])
            self.assertIn("needs.verify.result == 'success'", JOBS[job]["if"])
            self.assertIn("verify", needs(job))
        self.assertIn("reserve-release-tag", needs("images"))
        self.assertIn("images", needs("release"))
        self.assertIn("needs.metadata.outputs.deploy == 'true'", JOBS["deploy"]["if"])
        self.assertIn("needs.release.result == 'success'", JOBS["deploy"]["if"])

    def test_removed_workflows_unique_checks_are_preserved(self):
        e2e = scripts(JOBS["verify-e2e"])
        for check in ("localization.py validate", "localization.py context",
                      "generate-native-localization.py --check", "generate-font-resources.py --check", "e2e-tests/run.sh"):
            self.assertIn(check, e2e)
        worker = load_yaml(WORKFLOWS / "windows-worker-service.yml")
        text = str(worker)
        for check in ("ubuntu-latest", "windows-latest", "JvmComputerUseControllerTest", "GrzCaptureScreenshotToolImplTest",
                      "GrzComputerUseToolsImplTest", "LocalCommandProcessRunnerTest", "JsonSchemaGeneratorTest",
                      "DefaultCommandTaskServiceTest", "DefaultCommandMonitorServiceTest", "WorkerCommandRuntimeGatewayHandlerTest",
                      "GrzCommandMonitorToolsTest", "test-windows-worker-service.ps1"):
            self.assertIn(check, text)

    def test_no_worker_image_dependency(self):
        self.assertFalse((ROOT / "deploy/docker/worker.Dockerfile").exists())
        context = (ROOT / ".dockerignore").read_text()
        self.assertNotIn("worker", context)
        self.assertNotIn("browser-mcp", context)
        compose = load_yaml(ROOT / "deploy/distribution/compose.yaml")
        self.assertNotIn("worker", compose["services"])
        self.assertNotIn("gromozeka-worker-home", compose["volumes"])
        for filename in ("release.yml", "deploy-release.yml"):
            text = (WORKFLOWS / filename).read_text()
            self.assertNotIn("WORKER_REPOSITORY", text)
            self.assertNotIn("worker.Dockerfile", text)
            self.assertNotIn("ghcr.io/lewik/gromozeka-worker", text)


class VersionResolver(unittest.TestCase):
    def resolve(self, *, tags="v4.7.2\nv4.8.0\n", succeeds=True, **inputs):
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("INPUT_", "GITHUB_"))}
        env.update(inputs)
        result = subprocess.run(["node", str(ROOT / "scripts/resolve-release-version.mjs")],
                                input=tags, text=True, capture_output=True, env=env, check=False)
        if not succeeds:
            self.assertNotEqual(0, result.returncode)
            return result.stderr
        self.assertEqual(0, result.returncode, result.stderr)
        return dict(line.split("=", 1) for line in result.stdout.splitlines())

    def test_build_only_and_push_never_implicitly_publish(self):
        for event in ("workflow_dispatch", "push"):
            result = self.resolve(GITHUB_EVENT_NAME=event, GITHUB_REF_NAME="v4.8.0")
            self.assertEqual("false", result["publish"])
            self.assertEqual("false", result["deploy"])
            self.assertEqual("4.8.1", result["version"])

    def test_generated_bumps_use_remote_tags(self):
        for bump, version in (("patch", "4.8.1"), ("minor", "4.9.0"), ("major", "5.0.0")):
            self.assertEqual(version, self.resolve(INPUT_BUMP=bump)["version"])

    def test_publication_is_not_deployment(self):
        result = self.resolve(INPUT_PUBLISH_RELEASE="true", INPUT_BUMP="minor")
        self.assertEqual("true", result["publish"])
        self.assertEqual("false", result["deploy"])
        self.assertEqual("v4.9.0", result["tag"])
        self.assertIn("requires publish_release=true", self.resolve(succeeds=False, INPUT_DEPLOY_AWS="true"))

    def test_reserved_tags_cannot_be_reused_or_downgraded(self):
        for version in ("4.7.2", "4.8.0", "4.7.9", "0.0.0-dev", "04.9.0"):
            with self.subTest(version=version):
                self.resolve(succeeds=False, INPUT_PUBLISH_RELEASE="true", INPUT_VERSION=version)
        self.resolve(tags="v4.8.0\nv4.9.0-rc.1\n", succeeds=False,
                     INPUT_PUBLISH_RELEASE="true", INPUT_VERSION="4.8.1")


if __name__ == "__main__":
    unittest.main(verbosity=2)
