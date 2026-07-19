# Gromozeka Worker

The Worker is a standalone process. The Server never starts an embedded Worker.

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

Configure `workspaces` for filesystem trees visible on that Worker. Each entry
declares the logical Project id, stable Workspace id, display names, and the
Worker-local root path. Two different checkouts of one Project are different
Workspaces. Multiple Workers may mount one Workspace only when they see the
same underlying tree.

At startup the Worker creates or validates the central Project and Workspace,
then attaches its local mount. Tool calls carry an exact Worker and Workspace
target. They are never reassigned or retried on another Worker automatically.

The current deployment contract gives Workers direct access to PostgreSQL and
RabbitMQ. These endpoints must stay on a private network and use TLS. A future
control plane can replace direct credentials without changing runtime task,
claim, capability, or exact-target semantics.
