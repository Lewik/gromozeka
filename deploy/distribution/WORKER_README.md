# Gromozeka Worker

Gromozeka Worker is a trusted, unsandboxed executor. Enrolling or configuring a
Worker authorizes the Gromozeka control plane and its selected models to invoke
configured tools with the effective permissions of the Worker process.

Open the Server `/downloads` page, generate a one-time enrollment token, and
run the command shown there. Enrollment writes the Worker configuration to
`~/.gromozeka/worker.yaml`.

Manual configuration remains available for development: copy
`config/worker.yaml.example` to `~/.gromozeka/worker.yaml`, provide the required
PostgreSQL and RabbitMQ environment variables, then run:

- macOS/Linux: `bin/gromozeka-worker`
- Windows: `bin\gromozeka-worker.cmd`

The bundled Java runtime is private to the Worker and does not modify the
machine-wide Java installation.
