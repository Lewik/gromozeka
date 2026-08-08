# Gromozeka Worker

Gromozeka Worker is a trusted, unsandboxed executor. Enrolling or configuring a
Worker authorizes the Gromozeka control plane and its selected models to invoke
configured tools with the effective permissions of the Worker process.

The macOS and Windows Gromozeka apps already include an optional Local Worker.
Normal desktop installations should enable it from **Settings -> Advanced ->
This Mac/PC**. This standalone archive remains useful for headless machines,
remote Workers, and multiple Worker processes on one host.

Open the Server `/downloads` page and run the connection command shown there.
The Worker prints a short code. Review and approve it in **Settings -> Security**
on an authorized Gromozeka Client. The Worker then writes its private
configuration to `~/.gromozeka/worker.yaml`.

For unattended provisioning, an Owner can still generate a short-lived
one-time enrollment token from the advanced section of the downloads page and
run `bin/gromozeka-worker enroll`.

When the Server uses a private or corporate CA that is not in the bundled Java
trust store, append `--ca-certificate /path/to/root-or-chain.pem`. Connection
copies the CA beside the Worker configuration and uses system trust plus that
CA for HTTPS and WSS.

Manual configuration remains available for development: copy
`config/worker.yaml.example` to `~/.gromozeka/worker.yaml`, provide the Server
URL and Worker Gateway credential, then run:

- macOS/Linux: `bin/gromozeka-worker`
- Windows: `bin\gromozeka-worker.cmd`

On macOS, install the Worker as a per-user LaunchAgent with:

```bash
bin/gromozeka-worker install-service
```

The command installs and signs `Gromozeka Worker.app` once outside the versioned
archive. Worker updates preserve that launcher so macOS permissions remain
valid. When Computer Use is enabled, run
`bin/gromozeka-worker open-computer-use-permissions`, approve Screen Recording
and Accessibility in **System Settings -> Privacy & Security**, then run
`install-service` again. Use `start-service`, `stop-service`, `service-status`,
and `uninstall-service` for the remaining service management commands.

The package includes pinned, checksum-verified Eclipse Temurin 21 and Node.js
runtimes. Launchers always use those private runtimes, do not change system
Java or Node.js, and do not download executable code during startup.

The Worker connects outbound to the Server over WSS. It does not receive
PostgreSQL credentials and does not share the Server data directory. Remote
plaintext connections are rejected.

Claude Code is not bundled with Gromozeka. Install, license, and authenticate it
separately on this Worker before enabling a Claude Code connection.

The standalone Worker includes Gromozeka Browser MCP and its Node.js runtime.
Browser binaries are not bundled; Browser Use connects to the separately
installed Browser Bridge.
