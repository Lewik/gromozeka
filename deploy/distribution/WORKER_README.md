# Gromozeka Worker

Gromozeka Worker is a trusted, unsandboxed executor. Enrolling or configuring a
Worker authorizes the Gromozeka control plane and its selected models to invoke
configured tools with the effective permissions of the Worker process.

Open the Server `/downloads` page, generate a one-time enrollment token, and
run the command shown there. Enrollment writes the Worker configuration to
`~/.gromozeka/worker.yaml`.

When the Server uses a private or corporate CA that is not in the bundled Java
trust store, append `--ca-certificate /path/to/root-or-chain.pem`. Enrollment
copies the CA beside the Worker configuration and uses system trust plus that
CA for HTTPS and WSS.

Manual configuration remains available for development: copy
`config/worker.yaml.example` to `~/.gromozeka/worker.yaml`, provide the Server
URL and Worker Gateway credential, then run:

- macOS/Linux: `bin/gromozeka-worker`
- Windows: `bin\gromozeka-worker.cmd`

The bundled Java runtime is private to the Worker and does not modify the
machine-wide Java installation.

The Worker connects outbound to the Server over WSS. It does not receive
PostgreSQL credentials and does not share the Server data directory. Remote
plaintext connections are rejected.

Claude Code is not bundled with Gromozeka. Install, license, and authenticate it
separately on this Worker before enabling a Claude Code connection.
