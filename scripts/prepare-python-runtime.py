#!/usr/bin/env python3
"""Assemble a pinned, offline-at-runtime Python distribution for a desktop Worker.

Build-time Python 3.14+ is required for the upstream Zstandard license archive.
No packages are resolved or installed on the user's machine. Only hash-verified
archives from python-runtime.json are consumed; downloads are never retried.
"""
import argparse
import email.parser
import email.policy
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = ROOT / "deploy/distribution/python-runtime.json"
MARKER = "gromozeka-runtime.json"


def sha256_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require_relative(name):
    path = PurePosixPath(name)
    if not name or not path.parts or path.is_absolute() or ".." in path.parts or "\\" in name or ":" in path.parts[0]:
        raise ValueError("Invalid archive path: " + name)
    return path


def artifact_file(artifact, cache, offline):
    filename = artifact["filename"]
    if require_relative(filename).name != filename:
        raise ValueError("Artifact filename must not contain directories")
    digest = artifact["sha256"]
    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise ValueError("Invalid artifact SHA-256: " + filename)
    destination = cache / (digest + "-" + filename)
    if destination.exists():
        if not destination.is_file() or sha256_file(destination) != digest:
            raise ValueError("Cached artifact checksum mismatch: " + filename)
        return destination
    if offline:
        raise FileNotFoundError("Artifact is not cached and offline mode is enabled: " + filename)
    url = artifact["url"]
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        raise ValueError("Artifact URL must be HTTPS without credentials")
    cache.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=".download-", dir=cache)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as output:
            request = urllib.request.Request(url, headers={"User-Agent": "Gromozeka-runtime-builder"})
            with urllib.request.urlopen(request, timeout=120) as response:
                shutil.copyfileobj(response, output)
        if sha256_file(temporary) != digest:
            raise ValueError("Downloaded artifact checksum mismatch: " + filename)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def add_wheel(wheel_path, package, site_packages):
    with zipfile.ZipFile(wheel_path) as wheel:
        names = wheel.namelist()
        for item in wheel.infolist():
            require_relative(item.filename)
            if (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Wheel contains a symbolic link")
            if any(part.endswith(".data") for part in PurePosixPath(item.filename).parts):
                raise ValueError("The managed SDK accepts purelib wheels without .data installation rules")
        metadata_paths = [name for name in names if name.endswith(".dist-info/METADATA")]
        wheel_paths = [name for name in names if name.endswith(".dist-info/WHEEL")]
        if len(metadata_paths) != 1 or len(wheel_paths) != 1:
            raise ValueError("Invalid wheel metadata layout")
        parser = email.parser.BytesParser(policy=email.policy.default)
        metadata = parser.parsebytes(wheel.read(metadata_paths[0]))
        wheel_metadata = parser.parsebytes(wheel.read(wheel_paths[0]))
        normalize = lambda value: value.lower().replace("-", "_").replace(".", "_")
        if normalize(metadata["Name"]) != normalize(package["name"]) or metadata["Version"] != package["version"]:
            raise ValueError("Wheel identity does not match the lock")
        if wheel_metadata["Root-Is-Purelib"] != "true" or wheel_metadata.get_all("Tag") != ["py3-none-any"]:
            raise ValueError("The managed SDK requires platform-independent pure Python wheels")
        declared_license = metadata["License-Expression"] or metadata["License"]
        if declared_license != package["license"]:
            raise ValueError("Wheel license declaration does not match the lock: " + package["name"])
        license_files = [name for name in names if ".dist-info/" in name and
                         PurePosixPath(name).name.lower().startswith(("license", "copying", "notice"))]
        if not license_files:
            raise ValueError("Wheel has no bundled license text: " + package["name"])
        for item in wheel.infolist():
            relative = require_relative(item.filename)
            destination = site_packages.joinpath(*relative.parts)
            if item.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                if destination.exists():
                    raise ValueError("Wheel overwrites an existing runtime file: " + item.filename)
                destination.parent.mkdir(parents=True, exist_ok=True)
                with wheel.open(item) as source, destination.open("xb") as output:
                    shutil.copyfileobj(source, output)


def add_native_licenses(archive_path, runtime, target, python_version):
    legal = runtime / "legal/python-build-standalone"
    legal.mkdir(parents=True)
    metadata = None
    with tarfile.open(archive_path, "r|zst") as archive:
        for member in archive:
            if not member.isfile():
                continue
            if member.name == "python/PYTHON.json":
                data = archive.extractfile(member).read()
                metadata = json.loads(data)
                (legal / "PYTHON.json").write_bytes(data)
            elif member.name.startswith("python/licenses/"):
                relative = require_relative(member.name.removeprefix("python/"))
                destination = legal.joinpath(*relative.parts)
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as source, destination.open("xb") as output:
                    shutil.copyfileobj(source, output)
    if metadata is None or metadata["target_triple"] != target["triple"] or metadata["python_version"] != python_version:
        raise ValueError("Native license archive does not describe the selected Python distribution")

    def validate_references(value):
        if isinstance(value, dict):
            paths = value.get("license_paths", [])
            if "license_path" in value:
                paths = [*paths, value["license_path"]]
            for name in paths:
                if not legal.joinpath(*require_relative(name).parts).is_file():
                    raise ValueError("Upstream license text is missing: " + name)
            for nested in value.values():
                validate_references(nested)
        elif isinstance(value, list):
            for nested in value:
                validate_references(nested)

    validate_references(metadata)


def validate_runtime(destination, marker, target):
    marker_path = destination / MARKER
    if not marker_path.is_file() or json.loads(marker_path.read_text(encoding="utf-8")) != marker:
        raise ValueError("Existing runtime does not match this build; select a new destination: " + str(destination))
    if not destination.joinpath(*require_relative(target["executable"]).parts).is_file():
        raise ValueError("Prepared Python executable is missing")
    if not (destination / "legal/python-build-standalone/PYTHON.json").is_file():
        raise ValueError("Prepared Python license provenance is missing")
    site = destination.joinpath(*require_relative(target["site_packages"]).parts)
    for name, version in marker["packages"].items():
        metadata = site / (name.replace("-", "_") + "-" + version + ".dist-info/METADATA")
        if not metadata.is_file():
            raise ValueError("Prepared Python package metadata is missing: " + name)


def prepare(manifest_path, target_name, destination, cache, offline=False):
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    if manifest["format"] != 1 or target_name not in manifest["targets"]:
        raise ValueError("Unsupported Python manifest or target: " + target_name)
    target = manifest["targets"][target_name]
    marker = {"format": 1, "lock_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
              "target": target_name, "python_version": manifest["python_version"],
              "packages": {p["name"]: p["version"] for p in manifest["packages"]}}
    if len(marker["packages"]) != len(manifest["packages"]):
        raise ValueError("Duplicate package in Python runtime lock")
    if destination.exists():
        if not destination.is_dir() or destination.is_symlink():
            raise ValueError("Runtime destination is not a directory: " + str(destination))
        if any(destination.iterdir()):
            validate_runtime(destination, marker, target)
            return
        # Gradle creates declared output directories before executing an Exec task.
        destination.rmdir()
    runtime_archive = artifact_file(target["runtime"], cache, offline)
    license_archive = artifact_file(target["license_archive"], cache, offline)
    wheels = [(package, artifact_file(package, cache, offline)) for package in manifest["packages"]]
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".python-", dir=destination.parent) as temporary:
        stage = Path(temporary)
        with tarfile.open(runtime_archive, "r:gz") as archive:
            archive.extractall(stage, filter="data")
        runtime = stage / "python"
        if not runtime.is_dir():
            raise ValueError("Python archive has no expected root directory")
        add_native_licenses(license_archive, runtime, target, manifest["python_version"])
        site_packages = runtime.joinpath(*require_relative(target["site_packages"]).parts)
        if not site_packages.is_dir():
            raise ValueError("Python archive has no expected site-packages directory")
        for package, wheel in wheels:
            add_wheel(wheel, package, site_packages)
        (runtime / MARKER).write_text(json.dumps(marker, indent=2) + "\n", encoding="utf-8")
        (runtime / "legal/python-runtime-lock.json").write_bytes(manifest_bytes)
        validate_runtime(runtime, marker, target)
        # Do not overwrite a generation which a running script may still use.
        runtime.rename(destination)


def main():
    if sys.version_info < (3, 14):
        raise RuntimeError("Preparing the bundled runtime requires build-time Python 3.14 or newer")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=("macos-arm64", "linux-x64", "windows-x64"))
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--cache", type=Path, default=ROOT / "build/bundled-runtime-cache/python/artifacts")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    prepare(args.manifest.resolve(), args.target, args.destination.resolve(), args.cache.resolve(), args.offline)
    print("Prepared bundled Python for " + args.target + ": " + str(args.destination.resolve()))


if __name__ == "__main__":
    main()
