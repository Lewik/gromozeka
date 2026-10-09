#!/usr/bin/env bash
set -euo pipefail

[[ "${GITHUB_ACTIONS:-}" == "true" ]] || {
  echo "Docker cache configuration is restricted to GitHub Actions runners" >&2
  exit 2
}

sudo python3 - <<'PY'
import json
from pathlib import Path

path = Path('/etc/docker/daemon.json')
config = json.loads(path.read_text()) if path.exists() else {}
mirrors = config.get('registry-mirrors', [])
config['registry-mirrors'] = list(dict.fromkeys(['https://mirror.gcr.io', *mirrors]))
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(config, indent=2) + '\n')
PY
sudo systemctl restart docker
docker info --format '{{json .RegistryConfig.Mirrors}}'
