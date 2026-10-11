import argparse
import plistlib
import subprocess
from pathlib import Path


def verify(app: Path):
    info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
    if info.get("CFBundleIdentifier") != "com.gromozeka.app":
        raise ValueError(f"Unexpected client bundle: {app}")
    if not info.get("NSMicrophoneUsageDescription", "").strip():
        raise ValueError("Client is missing the microphone permission description")

    subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
    signed = subprocess.run(
        ["codesign", "--display", "--entitlements", ":-", str(app)],
        check=True, capture_output=True,
    )
    entitlements = plistlib.loads(signed.stdout)
    required = (
        "com.apple.security.cs.allow-jit",
        "com.apple.security.cs.allow-unsigned-executable-memory",
        "com.apple.security.cs.disable-library-validation",
        "com.apple.security.device.audio-input",
    )
    missing = [key for key in required if entitlements.get(key) is not True]
    if missing:
        raise ValueError(f"Client signature is missing required entitlements: {', '.join(missing)}")
    print(f"Verified macOS client signature, JVM entitlements and microphone access: {app}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("app", type=Path)
    verify(parser.parse_args().app)
