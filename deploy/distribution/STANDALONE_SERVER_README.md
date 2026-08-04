# Gromozeka Standalone Server

This package contains the Gromozeka control plane and production Web client.
It uses Java 21 or newer from `GROMOZEKA_JAVA_EXECUTABLE`,
`GROMOZEKA_JAVA_HOME`, `JAVA_HOME`, or the system path. If none is compatible,
the launcher downloads the pinned Eclipse Temurin JRE once, verifies its
SHA-256 checksum, and stores it under `~/.gromozeka/runtimes`.

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
