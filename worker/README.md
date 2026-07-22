# Gromozeka Worker

The Worker is a standalone process. The Server never starts an embedded Worker.

## Trust Model

A Gromozeka Worker is a trusted, unsandboxed executor. Enrolling a Worker authorizes the Gromozeka control plane and its selected models to invoke configured tools with the effective permissions of the Worker process.

The Worker is not an autonomous agent and does not decide goals or policy. It executes durable tasks assigned by the control plane, including LLM, memory, and tool work when those capabilities are enabled. Gromozeka does not add per-command approvals, command denylists, or a filesystem sandbox. Run the Worker under the operating-system account, container, or virtual machine whose permissions represent the intended hard boundary. See [Always YOLO](../README.md#always-yolo) for the complete execution trust model.

Start a Worker with an external YAML file:

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION="file:$PWD/worker/config/cloud-worker.yaml" \
GROMOZEKA_MODE=prod \
./gradlew :worker:run -q
```

For the local dev stack started from this repository:

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION="file:$PWD/worker/config/dev-worker.yaml" \
GROMOZEKA_MODE=dev \
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" \
./gradlew :worker:run -q
```

Copy either `cloud-worker.example.yaml` or `local-worker.example.yaml` to an
untracked deployment config and provide credentials through environment
variables.

`id` is the stable Worker identity. Every process start creates a new session
identity, so two live processes cannot own the same Worker id.
Each Worker writes to a separate `<worker-id>.log` file under the mode-specific
Worker log directory.

A Worker durably claims a runtime task before acknowledging its RabbitMQ
delivery. The claim never expires or moves to another Worker session. After the
RabbitMQ acknowledgement, the Server records the execution-start boundary. If
the session disappears before that boundary, the task is recorded as not
started; after it, the outcome is unknown. Neither case is rerun automatically.

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

The current deployment contract gives Workers direct access to PostgreSQL and
RabbitMQ. These endpoints must stay on a private network and use TLS. A future
control plane can replace direct credentials without changing runtime task,
claim, capability, or exact-target semantics.
