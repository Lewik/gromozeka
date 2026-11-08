#!/usr/bin/env python3
"""Run native client tests with system services in an owned, disposable iOS simulator."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]


def simctl(*arguments, capture=False):
    result = subprocess.run(
        ["xcrun", "simctl", *arguments], check=True, text=True,
        stdout=subprocess.PIPE if capture else None,
        timeout=300 if arguments[:1] == ("bootstatus",) else 90,
    )
    return result.stdout.strip() if capture else None


def select_template(catalog):
    device_types = {item["identifier"]: item for item in catalog["devicetypes"]}
    runtimes = sorted(
        (item for item in catalog["runtimes"] if item["isAvailable"] and ".iOS-" in item["identifier"]),
        key=lambda item: tuple(int(part) for part in item["version"].split(".")), reverse=True,
    )
    for runtime in runtimes:
        for device in catalog["devices"].get(runtime["identifier"], []):
            kind = device_types.get(device.get("deviceTypeIdentifier"))
            if device["isAvailable"] and kind is not None and kind.get("productFamily") == "iPhone":
                return runtime["identifier"], kind["identifier"]
    raise RuntimeError("No available iOS iPhone simulator template; install an iOS runtime in Xcode")


def remove_owned_device(device, run=simctl):
    catalog = json.loads(run("list", "devices", "--json", capture=True))["devices"]
    matches = [item for group in catalog.values() for item in group if item["udid"] == device]
    if len(matches) != 1:
        raise RuntimeError(f"Cannot establish cleanup state for owned simulator {device}")
    if matches[0]["state"] != "Shutdown":
        run("shutdown", device)
    run("delete", device)


def run_tests(arguments, run=simctl, execute=subprocess.run, root=ROOT):
    if any(arg == "--device" or arg.startswith("--device=") for arg in arguments):
        raise ValueError("This wrapper selects and owns its simulator; do not override --device")
    runtime, kind = select_template(json.loads(run("list", "--json", capture=True)))
    state_directory = root / "build" / "ios-platform-tests"
    state_directory.mkdir(parents=True, exist_ok=True)
    device = run("create", f"Gromozeka client tests {uuid.uuid4().hex[:8]}", kind, runtime, capture=True)
    uuid.UUID(device)
    receipt = state_directory / f"{device}.json"
    print(f"Native client test simulator: {device}", flush=True)
    try:
        receipt.write_text(json.dumps({"device": device, "runtime": runtime, "owned": True}) + "\n")
        run("boot", device)
        run("bootstatus", device, "-b")
        with tempfile.TemporaryDirectory(prefix="gromozeka-ios-test-") as temporary:
            init = Path(temporary) / "booted.init.gradle"
            init.write_text("""gradle.beforeProject { p ->
    if (p.path == ':presentation') {
        p.afterEvaluate {
            // Standalone simulators do not initialize the system UTI conformance database.
            p.tasks.named('iosSimulatorArm64Test').configure { standalone.set(false) }
        }
    }
}
""")
            result = execute(
                [str(root / "gradlew"), "--no-daemon", "--init-script", str(init),
                 ":presentation:iosSimulatorArm64Test", "--device", device, *arguments],
                cwd=root,
            )
            return result.returncode
    finally:
        remove_owned_device(device, run)
        receipt.unlink(missing_ok=True)
        print(f"Removed native client test simulator: {device}", flush=True)


if __name__ == "__main__":
    arguments = sys.argv[1:]
    if arguments[:1] == ["--"]:
        arguments = arguments[1:]
    sys.exit(run_tests(arguments))
