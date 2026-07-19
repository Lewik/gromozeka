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

For project-local tools, configure `project-paths` with directories visible on
that Worker. At startup the Worker validates each path, resolves its central
Project id, and registers the corresponding project affinity. Runtime tasks
carry only that stable Project id, never a machine-local path as routing data.

The current deployment contract gives Workers direct access to PostgreSQL and
RabbitMQ. These endpoints must stay on a private network and use TLS. A future
control plane can replace direct credentials without changing runtime task,
claim, capability, or affinity semantics.
