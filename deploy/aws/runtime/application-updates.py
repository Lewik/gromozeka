#!/usr/bin/env python3
"""Create and stage immutable, private application update packages."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tarfile
import tempfile

MAX_PACKAGE_BYTES = 2 * 1024 * 1024 * 1024
PACKAGES = {
    "gromozeka-client-macos-arm64.zip": ("CLIENT", "MACOS_ARM64", "MACOS_APP_ZIP"),
    "gromozeka-client-windows-x64.zip": ("CLIENT", "WINDOWS_X64", "WINDOWS_ZIP"),
    "gromozeka-worker-macos-arm64.tar.gz": ("WORKER", "MACOS_ARM64", "WORKER_TAR_GZ"),
    "gromozeka-worker-windows-x64.zip": ("WORKER", "WINDOWS_X64", "WINDOWS_ZIP"),
    "gromozeka-client-android.apk": ("CLIENT", "ANDROID", "APK"),
    "gromozeka-worker-android.apk": ("WORKER", "ANDROID", "APK"),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_version(version):
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version), "Invalid version")


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def validate_catalog(data, version):
    validate_version(version)
    require(data["schemaVersion"] == 1 and data["version"] == version, "Wrong catalogue version")
    artifacts = data["artifacts"]
    require(4 <= len(artifacts) <= len(PACKAGES), "Missing desktop packages")
    names = [item["fileName"] for item in artifacts]
    require(len(names) == len(set(names)) and set(names) in (set(PACKAGES), set(list(PACKAGES)[:4])), "Unexpected package set")
    for item in artifacts:
        require((item["component"], item["platform"], item["format"]) == PACKAGES[item["fileName"]], "Wrong package identity")
        require(item["version"] == version and type(item["size"]) is int and 0 < item["size"] <= MAX_PACKAGE_BYTES, "Invalid package version or size")
        require(re.fullmatch(r"[a-f0-9]{64}", item["sha256"]), "Invalid checksum")
    return artifacts


def verify_files(directory, artifacts):
    for item in artifacts:
        path = directory / item["fileName"]
        require(not path.is_symlink() and path.is_file() and path.stat().st_size == item["size"], "Package size mismatch")
        require(digest(path) == item["sha256"], "Package checksum mismatch")


def create_catalog(version, directory):
    artifacts = []
    for name, (component, platform, package_format) in PACKAGES.items():
        path = directory / name
        if not path.exists() and platform == "ANDROID":
            continue
        require(path.is_file() and not path.is_symlink(), f"Missing package: {name}")
        artifacts.append(dict(component=component, platform=platform, version=version, fileName=name,
                              size=path.stat().st_size, sha256=digest(path), format=package_format))
    catalog = dict(schemaVersion=1, version=version, artifacts=artifacts)
    validate_catalog(catalog, version)
    (directory / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")


def bundle(version, directory, destination):
    catalog = json.loads((directory / "catalog.json").read_text())
    artifacts = validate_catalog(catalog, version)
    verify_files(directory, artifacts)
    checksums = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        checksum, name = line.split(maxsplit=1)
        checksums[name] = checksum
    names = ["catalog.json"] + [item["fileName"] for item in artifacts]
    for name in names:
        require(checksums.get(name) == digest(directory / name), f"Release checksum mismatch: {name}")
    with tarfile.open(destination, "w") as archive:
        for name in names:
            archive.add(directory / name, arcname=name, recursive=False)


def stage(version, source, root):
    validate_version(version)
    root.mkdir(parents=True, exist_ok=True)
    require(not root.is_symlink(), "Invalid update root")
    destination = root / version
    with tempfile.TemporaryDirectory(prefix=".staging-", dir=root) as temporary:
        staging = Path(temporary)
        with tarfile.open(source, "r:") as archive:
            members = []
            for member in archive:
                members.append(member)
                require(len(members) <= 7 and member.isfile(), "Invalid update archive")
            names = [member.name for member in members]
            require(5 <= len(names) == len(set(names)) and set(names) <= set(PACKAGES) | {"catalog.json"}, "Invalid archive entries")
            manifest = archive.getmember("catalog.json")
            require(0 < manifest.size <= 65536, "Invalid catalogue size")
            catalog_bytes = archive.extractfile(manifest).read()
            artifacts = validate_catalog(json.loads(catalog_bytes), version)
            require(set(names) == {"catalog.json"} | {item["fileName"] for item in artifacts}, "Archive does not match catalogue")
            for item in artifacts:
                member = archive.getmember(item["fileName"])
                require(member.size == item["size"], "Package size mismatch")
                with archive.extractfile(member) as incoming, (staging / member.name).open("wb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
                    outgoing.flush()
                    os.fsync(outgoing.fileno())
            (staging / "catalog.json").write_bytes(catalog_bytes)
        verify_files(staging, artifacts)
        if destination.exists() or destination.is_symlink():
            require(destination.is_dir() and not destination.is_symlink(), "Invalid release directory")
            require((destination / "catalog.json").read_bytes() == catalog_bytes, "Published update catalogue is immutable")
            verify_files(destination, artifacts)
            return
        for path in staging.iterdir():
            path.chmod(0o644)
        staging.chmod(0o755)
        os.rename(staging, destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["catalog", "bundle", "stage"])
    parser.add_argument("version")
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path, nargs="?")
    args = parser.parse_args()
    if args.command == "catalog":
        create_catalog(args.version, args.source)
    else:
        if args.destination is None:
            parser.error("destination is required")
        (bundle if args.command == "bundle" else stage)(args.version, args.source, args.destination)


if __name__ == "__main__":
    main()
