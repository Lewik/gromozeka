# Gromozeka Standalone Server

This package contains the Gromozeka control plane and production Web client.
It includes a pinned, checksum-verified Eclipse Temurin 21 JRE. The launcher
always uses that private runtime, does not change system Java, and does not
download executable code during startup.

PostgreSQL with pgvector remains an external service. Copy
`config/server.yaml.example` to `~/.gromozeka/server.yaml` and change its
connection settings when PostgreSQL is not running locally with the shown
development credentials.

Run:

- macOS/Linux: `bin/gromozeka-server`
- Windows: `bin\gromozeka-server.cmd`

The Server listens on `127.0.0.1:8765` by default. Put TLS and external network
policy in a reverse proxy, VPN, firewall, or operating-system configuration.
The Docker Compose Server stack remains the simplest self-contained deployment.
