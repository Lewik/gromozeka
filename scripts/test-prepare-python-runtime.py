"""Offline packaging regressions using synthetic archives, never a user environment."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

spec = importlib.util.spec_from_file_location("prepare_python_runtime", Path(__file__).with_name("prepare-python-runtime.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class PreparationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="grz-python-package-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.cache = self.root / "cache"
        self.cache.mkdir()
        self.destination = self.root / "prepared"
        self.package = {"name": "tiny", "version": "1.0", "license": "MIT"}
        runtime = self.tar_artifact("runtime.tar.gz", "w:gz", {
            "python/bin/python3.14": b"synthetic interpreter",
            "python/lib/python3.14/site-packages/existing.txt": b"existing",
        })
        licenses = self.tar_artifact("licenses.tar.zst", "w:zst", {
            "python/PYTHON.json": json.dumps({"target_triple": "synthetic-target", "python_version": "3.14.10",
                                              "license_path": "licenses/LICENSE.cpython.txt"}).encode(),
            "python/licenses/LICENSE.cpython.txt": b"synthetic Python license",
        })
        self.manifest = {"format": 1, "python_version": "3.14.10", "targets": {"macos-arm64": {
            "triple": "synthetic-target", "runtime": runtime, "license_archive": licenses,
            "executable": "bin/python3.14", "site_packages": "lib/python3.14/site-packages",
        }}, "packages": [{**self.package, **self.wheel_artifact()}]}
        self.manifest_path = self.root / "lock.json"
        self.write_manifest()

    def cache_artifact(self, filename, data):
        digest = hashlib.sha256(data).hexdigest()
        (self.cache / (digest + "-" + filename)).write_bytes(data)
        return {"filename": filename, "sha256": digest, "url": "https://invalid.example/" + filename}

    def tar_artifact(self, filename, mode, files):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode=mode) as archive:
            for name, content in files.items():
                member = tarfile.TarInfo(name)
                member.size = len(content)
                member.mode = 0o755 if "/bin/" in name else 0o644
                archive.addfile(member, io.BytesIO(content))
        return self.cache_artifact(filename, stream.getvalue())

    def wheel_artifact(self, license_name="MIT", extra=None):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as wheel:
            wheel.writestr("tiny.py", "VALUE = 42\n")
            wheel.writestr("tiny-1.0.dist-info/METADATA", "Metadata-Version: 2.4\nName: tiny\nVersion: 1.0\nLicense-Expression: " + license_name + "\n")
            wheel.writestr("tiny-1.0.dist-info/WHEEL", "Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: py3-none-any\n")
            wheel.writestr("tiny-1.0.dist-info/licenses/LICENSE", "synthetic package license")
            if extra:
                wheel.writestr(*extra)
        return self.cache_artifact("tiny-1.0-py3-none-any.whl", stream.getvalue())

    def write_manifest(self):
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")

    def prepare(self):
        builder.prepare(self.manifest_path, "macos-arm64", self.destination, self.cache, offline=True)

    def test_builds_offline_and_reuses_same_generation(self):
        with mock.patch.object(builder.urllib.request, "urlopen", side_effect=AssertionError("unexpected network")):
            self.prepare()
            marker = self.destination / builder.MARKER
            before = marker.stat().st_mtime_ns
            self.prepare()
            self.assertEqual(before, marker.stat().st_mtime_ns)
        self.assertTrue((self.destination / "lib/python3.14/site-packages/tiny.py").is_file())
        self.assertTrue((self.destination / "legal/python-build-standalone/licenses/LICENSE.cpython.txt").is_file())
        self.assertEqual(self.manifest_path.read_bytes(), (self.destination / "legal/python-runtime-lock.json").read_bytes())

    def test_accepts_empty_directory_created_by_gradle(self):
        self.destination.mkdir()
        self.prepare()
        self.assertTrue((self.destination / builder.MARKER).is_file())

    def test_never_overwrites_unknown_existing_directory(self):
        self.destination.mkdir()
        sentinel = self.destination / "running-program"
        sentinel.write_text("keep")
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertEqual("keep", sentinel.read_text())

    def test_corrupt_cache_is_not_redownloaded_or_used(self):
        entry = self.manifest["packages"][0]
        (self.cache / (entry["sha256"] + "-" + entry["filename"])).write_bytes(b"corrupt")
        with mock.patch.object(builder.urllib.request, "urlopen", side_effect=AssertionError("unexpected retry")):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                self.prepare()
        self.assertFalse(self.destination.exists())

    def test_license_change_is_a_build_error(self):
        self.manifest["packages"][0] = {**self.package, **self.wheel_artifact(license_name="Other")}
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "license declaration"):
            self.prepare()
        self.assertFalse(self.destination.exists())

    def test_missing_native_license_is_a_build_error(self):
        target = self.manifest["targets"]["macos-arm64"]
        target["license_archive"] = self.tar_artifact("missing-license.tar.zst", "w:zst", {
            "python/PYTHON.json": json.dumps({"target_triple": "synthetic-target", "python_version": "3.14.10",
                                              "license_path": "licenses/missing.txt"}).encode(),
        })
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "license text is missing"):
            self.prepare()
        self.assertFalse(self.destination.exists())

    def test_rejects_traversal_and_drive_paths(self):
        for name in ("", ".", "/absolute", "../escape", "x/../../escape", "C:/escape", "x\\escape"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                builder.require_relative(name)

    def test_rejects_symlink_in_wheel(self):
        entry = zipfile.ZipInfo("escape")
        entry.create_system = 3
        entry.external_attr = 0o120777 << 16
        self.manifest["packages"][0] = {**self.package, **self.wheel_artifact(extra=(entry, "../escape"))}
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            self.prepare()
        self.assertFalse(self.destination.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
