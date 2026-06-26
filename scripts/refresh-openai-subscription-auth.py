#!/usr/bin/env python3
import argparse
import base64
import hashlib
import json
import os
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def main() -> int:
    args = parse_args()
    config_path = resolve_config_path(args)
    config = load_config(config_path)
    redirect = parse_redirect(config)
    backup_config(config_path)

    verifier = base64url(secrets.token_bytes(32))
    state = base64url(secrets.token_bytes(32))
    challenge = base64url(hashlib.sha256(verifier.encode()).digest())
    write_pending_auth(config_path, config, verifier, state)

    authorize_url = build_authorize_url(config, challenge, state)
    port_available = is_port_available(redirect.hostname, redirect.port)
    if port_available:
        return run_standalone_callback(config_path, config, redirect, verifier, state, authorize_url, args.timeout_seconds)

    print(f"Callback port {redirect.port} is already in use; using the running Gromozeka callback server.")
    open_authorize_url(authorize_url)
    return wait_for_existing_callback(config_path, args.timeout_seconds)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Refresh local Gromozeka OpenAI subscription auth session.")
    parser.add_argument(
        "--home",
        default=os.environ.get("GROMOZEKA_HOME"),
        help="Gromozeka home directory. Defaults to GROMOZEKA_HOME, dev-data/client/.gromozeka, or ~/.gromozeka.",
    )
    parser.add_argument("--timeout-seconds", type=int, default=180)
    return parser.parse_args()


def resolve_config_path(args: argparse.Namespace) -> Path:
    if args.home:
        home = Path(args.home).expanduser()
    elif Path("dev-data/client/.gromozeka").is_dir():
        home = Path("dev-data/client/.gromozeka")
    else:
        home = Path.home() / ".gromozeka"

    config_path = home / "openai-subscription.json"
    if not config_path.exists():
        raise SystemExit(f"OpenAI subscription config does not exist: {config_path}")
    return config_path


def load_config(config_path: Path) -> dict:
    config = json.loads(config_path.read_text())
    for key in ("clientId", "issuer", "redirectUri", "scope"):
        if not config.get(key):
            raise SystemExit(f"OpenAI subscription config is missing {key}: {config_path}")
    return config


def parse_redirect(config: dict) -> urllib.parse.ParseResult:
    redirect = urllib.parse.urlparse(config["redirectUri"])
    if redirect.scheme != "http":
        raise SystemExit(f"Unsupported redirect scheme: {redirect.scheme}")
    if redirect.hostname not in ("localhost", "127.0.0.1"):
        raise SystemExit(f"Unexpected redirect host: {redirect.hostname}")
    if redirect.port is None:
        raise SystemExit("Redirect URI must include an explicit port.")
    return redirect


def backup_config(config_path: Path) -> None:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    backup_path = config_path.with_name(f"{config_path.name}.backup-{timestamp}")
    backup_path.write_text(config_path.read_text())
    print(f"Backup written: {backup_path}")


def write_pending_auth(config_path: Path, config: dict, verifier: str, state: str) -> None:
    config.update(
        {
            "pendingVerifier": verifier,
            "pendingState": state,
            "pendingDeviceCode": None,
            "accessToken": None,
            "refreshToken": None,
            "idToken": None,
            "accountId": None,
            "expiresAt": None,
        }
    )
    write_config(config_path, config)


def build_authorize_url(config: dict, challenge: str, state: str) -> str:
    params = {
        "response_type": "code",
        "client_id": config["clientId"],
        "scope": config["scope"],
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "id_token_add_organizations": "true",
        "codex_cli_simplified_flow": "true",
        "state": state,
        "redirect_uri": config["redirectUri"],
    }
    return config["issuer"].rstrip("/") + "/oauth/authorize?" + urllib.parse.urlencode(params)


def is_port_available(host: str | None, port: int | None) -> bool:
    if port is None:
        return False
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(0.5)
        return sock.connect_ex((host or "localhost", port)) != 0


def run_standalone_callback(
    config_path: Path,
    config: dict,
    redirect: urllib.parse.ParseResult,
    verifier: str,
    state: str,
    authorize_url: str,
    timeout_seconds: int,
) -> int:
    result: dict[str, str] = {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, _format: str, *_args: object) -> None:
            return

        def do_GET(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != redirect.path:
                self.send_response(404)
                self.end_headers()
                return

            query = urllib.parse.parse_qs(parsed.query)
            if query.get("error"):
                result["error"] = query.get("error", ["unknown"])[0] + ": " + query.get("error_description", [""])[0]
                send_html(self, 400, "Authentication Failed", "OpenAI returned an authentication error.")
                return

            code = query.get("code", [""])[0]
            returned_state = query.get("state", [""])[0]
            if not code:
                result["error"] = "Callback did not include authorization code."
                send_html(self, 400, "Missing Code", result["error"])
                return
            if returned_state != state:
                result["error"] = "Callback state did not match pending authorization."
                send_html(self, 400, "State Mismatch", result["error"])
                return

            result["code"] = code.split("#")[0]
            send_html(
                self,
                200,
                "Authentication Complete",
                "Gromozeka is now connected to your OpenAI subscription. You can close this tab.",
            )

    server = ThreadingHTTPServer((redirect.hostname or "localhost", redirect.port), Handler)
    server.timeout = 1
    try:
        open_authorize_url(authorize_url)
        started_at = time.time()
        while "code" not in result and "error" not in result and time.time() - started_at < timeout_seconds:
            server.handle_request()
    finally:
        server.server_close()

    if "error" in result:
        raise SystemExit(result["error"])
    if "code" not in result:
        raise SystemExit("Timed out waiting for OpenAI browser callback.")

    session = exchange_code_for_session(config, result["code"], verifier)
    config.update(session)
    config.update({"pendingVerifier": None, "pendingState": None, "pendingDeviceCode": None})
    write_config(config_path, config)
    print_success(config)
    return 0


def wait_for_existing_callback(config_path: Path, timeout_seconds: int) -> int:
    started_at = time.time()
    while time.time() - started_at < timeout_seconds:
        config = load_config(config_path)
        if config.get("accessToken") and config.get("refreshToken") and config.get("expiresAt"):
            print_success(config)
            return 0
        time.sleep(1)
    raise SystemExit("Timed out waiting for the running Gromozeka callback server to update the session.")


def open_authorize_url(authorize_url: str) -> None:
    print("OpenAI subscription login URL:")
    print(authorize_url)
    if sys.platform == "darwin":
        subprocess.run(["open", authorize_url], check=False)
    else:
        print("Open the URL in a browser and complete login.")


def send_html(handler: BaseHTTPRequestHandler, status: int, title: str, message: str) -> None:
    body = f"""
        <html>
          <head><meta charset="utf-8" /><title>{title}</title></head>
          <body style="font-family: sans-serif; max-width: 640px; margin: 40px auto; line-height: 1.5;">
            <h1>{title}</h1>
            <p>{message}</p>
          </body>
        </html>
    """.encode()
    handler.send_response(status)
    handler.send_header("Content-Type", "text/html; charset=utf-8")
    handler.end_headers()
    handler.wfile.write(body)


def exchange_code_for_session(config: dict, code: str, verifier: str) -> dict:
    data = urllib.parse.urlencode(
        {
            "grant_type": "authorization_code",
            "code": code,
            "client_id": config["clientId"],
            "code_verifier": verifier,
            "redirect_uri": config["redirectUri"],
        }
    ).encode()
    request = urllib.request.Request(
        config["issuer"].rstrip("/") + "/oauth/token",
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            token_response = json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        body = error.read().decode(errors="replace")
        raise SystemExit(f"OpenAI token exchange failed: {error.code} {body}") from error

    refresh_token = token_response.get("refresh_token")
    if not refresh_token:
        raise SystemExit("OpenAI token exchange did not return a refresh token.")

    id_token = token_response.get("id_token")
    access_token = token_response["access_token"]
    return {
        "accessToken": access_token,
        "refreshToken": refresh_token,
        "idToken": id_token,
        "accountId": extract_account_id(id_token or access_token),
        "expiresAt": int(time.time() * 1000) + int(token_response["expires_in"]) * 1000,
    }


def extract_account_id(token: str | None) -> str | None:
    claims = parse_jwt_claims(token)
    if not claims:
        return None
    if claims.get("chatgpt_account_id"):
        return claims["chatgpt_account_id"]
    nested = claims.get("https://api.openai.com/auth") or {}
    if nested.get("chatgpt_account_id"):
        return nested["chatgpt_account_id"]
    organizations = claims.get("organizations") or []
    if organizations and organizations[0].get("id"):
        return organizations[0]["id"]
    return None


def parse_jwt_claims(token: str | None) -> dict | None:
    if not token:
        return None
    parts = token.split(".")
    if len(parts) != 3:
        return None
    try:
        payload = parts[1] + "=" * ((4 - len(parts[1]) % 4) % 4)
        return json.loads(base64.urlsafe_b64decode(payload.encode()).decode())
    except Exception:
        return None


def base64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def write_config(config_path: Path, config: dict) -> None:
    config_path.write_text(json.dumps(config, indent=4) + "\n")


def print_success(config: dict) -> None:
    expires_at = datetime.fromtimestamp(config["expiresAt"] / 1000, timezone.utc).isoformat()
    account_state = "set" if config.get("accountId") else "not set"
    print(f"OpenAI subscription session refreshed. accountId={account_state} expiresAt={expires_at}")


if __name__ == "__main__":
    raise SystemExit(main())
