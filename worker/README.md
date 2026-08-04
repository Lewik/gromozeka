# Gromozeka Worker

The Worker is a standalone process. The Server never starts an embedded Worker.

## Trust Model

A Gromozeka Worker is a trusted, unsandboxed executor. Enrolling a Worker authorizes the Gromozeka control plane and its selected models to invoke configured tools with the effective permissions of the Worker process.

The Worker is not an autonomous agent and does not decide goals or policy. It executes exact Worker-targeted durable tasks assigned by the control plane, including configured tools and finite AI request-response operations. Conversation turns and memory pipelines remain on the Server. Gromozeka does not add per-command approvals, command denylists, or a filesystem sandbox. Run the Worker under the operating-system account, container, or virtual machine whose permissions represent the intended hard boundary. See [Always YOLO](../README.md#always-yolo) for the complete execution trust model.

Start a Worker with an external YAML file:

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION="file:$PWD/worker/config/cloud-worker.yaml" \
GROMOZEKA_MODE=prod \
./gradlew :worker:run -q
```

For the local dev stack, start the Server with
`GROMOZEKA_WORKER_ENROLLMENT_ENABLED=true`, generate a token in **Settings ->
Downloads**, and enroll once from the repository root:

```bash
./gradlew :worker:run \
  --args="enroll --server http://127.0.0.1:8765 --token <token> --worker-id local-dev --config $PWD/dev-data/client/.gromozeka/worker-dev.yaml --force" \
  -q
```

Start subsequent local dev Worker processes from the generated private config:

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION="file:$PWD/dev-data/client/.gromozeka/worker-dev.yaml" \
GROMOZEKA_MODE=dev \
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" \
./gradlew :worker:run -q
```

For manual configuration, copy `cloud-worker.example.yaml`,
`local-worker.example.yaml`, or `dev-worker.yaml` to an untracked deployment
config and provide credentials through environment variables.

`id` is the stable Worker identity. Every process start creates a new session
identity, so two live processes cannot own the same Worker id.
Each Worker writes to a separate `<worker-id>.log` file under the mode-specific
Worker log directory.

## Distribution

Release archives contain `gromozeka-worker.jar`, Gromozeka Browser MCP,
launchers, and an example configuration. They prefer compatible system Java
and Node runtimes and otherwise download checksum-pinned official runtimes once
under `~/.gromozeka/runtimes`. The GitHub release workflow builds macOS ARM64,
Linux x64, and Windows x64 packages.

Build a macOS or Linux archive locally:

```bash
npm --prefix browser-mcp ci
./gradlew :worker:bootJar -Pgromozeka.version=1.0.0 -q
deploy/distribution/package-standalone.sh worker macos arm64 build/release
```

When Server enrollment is enabled, open its `/downloads` page, generate a
one-time token, then run the displayed `bin/gromozeka-worker enroll` or
`bin\gromozeka-worker.cmd enroll` command. The Worker writes a private
`~/.gromozeka/worker.yaml`; manual YAML configuration remains available for
development and custom deployments.

Managed commands use the native host shell: `/bin/sh` on macOS/Linux and
`cmd.exe` on Windows. Windows commands are staged in managed batch artifacts
instead of being embedded in the `cmd.exe` argument list, and process-tree
cancellation uses `taskkill /T /F`.

The Worker opens one authenticated outbound WSS session to the Server. The
Server sends exact-target tool and finite AI operations through that session.
The Worker returns results and synchronizes command, monitor, Workspace Mount,
MCP, and environment state through the same narrow gateway. If the session
disappears after an operation starts, its outcome is unknown. Gromozeka never
reassigns or automatically retries the operation.

Worker YAML declares only stable identity, version, capabilities, and transport
credentials. Projects, Workspaces, and Workspace Mounts are central
server-managed data, not Worker startup configuration.

Creating or attaching a filesystem mount is an explicit tool operation routed
to the selected Worker. The Worker validates that the requested root path is an
existing local directory before persisting the mount. Two different checkouts
of one Project are different Workspaces. Multiple Workers may mount one
Workspace only when they see the same underlying tree. Later tool calls carry
an exact Worker or Workspace Mount target and are never reassigned or retried
automatically.

Workers never receive PostgreSQL credentials. Database access is a private
Server implementation detail. A Worker stores only its stable identity and
revocable Gateway credential; AI and MCP configuration is synchronized into
process memory after authentication.
