# Gromozeka Server

This package contains the Gromozeka control plane, production Web client, and a
private Java 21 runtime. PostgreSQL with pgvector and RabbitMQ remain external
services.

Copy `config/server.yaml.example` to `~/.gromozeka/server.yaml`, provide the
database and RabbitMQ environment variables referenced by that file, then run:

- macOS/Linux: `bin/gromozeka-server`
- Windows: `bin\gromozeka-server.cmd`

The Server listens on `127.0.0.1:8765` by default. Set
`GROMOZEKA_REMOTE_HOST=0.0.0.0` only when the surrounding operating-system,
VPN, firewall, or reverse-proxy configuration provides the intended network
boundary.

The bundled Java runtime is private to the Server and does not modify the
machine-wide Java installation.
