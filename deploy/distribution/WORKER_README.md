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

The launcher uses Java 21 or newer from `GROMOZEKA_JAVA_EXECUTABLE`,
`GROMOZEKA_JAVA_HOME`, `JAVA_HOME`, or the system path. If none is compatible,
it downloads the pinned Eclipse Temurin JRE once, verifies its SHA-256 checksum,
and stores it under `~/.gromozeka/runtimes` without changing system Java.

The Worker connects outbound to the Server over WSS. It does not receive
PostgreSQL credentials and does not share the Server data directory. Remote
plaintext connections are rejected.

Claude Code is not bundled with Gromozeka. Install, license, and authenticate it
separately on this Worker before enabling a Claude Code connection.

The standalone Worker includes Gromozeka Browser MCP. Browser Use prefers an
installed Node.js 20 or newer and otherwise downloads the pinned official Node
runtime into the same local cache on first use. Browser binaries are not
bundled; Browser Use connects to the separately installed Browser Bridge.
