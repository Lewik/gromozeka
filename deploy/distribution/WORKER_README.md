# Gromozeka Worker

Gromozeka Worker is a trusted, unsandboxed executor. Enrolling or configuring a
Worker authorizes the Gromozeka control plane and its selected models to invoke
configured tools with the effective permissions of the Worker process.

The Gromozeka Client does not bundle or manage a Worker. Install this standalone
archive separately on desktop, headless, or remote machines. Multiple Worker
processes may run on one host when each uses its own identity and configuration.

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

On Windows, connect the Worker first, then open PowerShell as administrator and
install the service from the extracted distribution:

```powershell
bin\gromozeka-worker.cmd install-service
```

The installer copies the distribution to `%ProgramFiles%\Gromozeka Worker` and
the connected configuration to `%ProgramData%\Gromozeka\Worker\worker.yaml`.
It preserves that configuration during updates. Stop any manual instance using
the same identity before installing the service. `GROMOZEKA_WORKER_CONFIG` can
select the configuration to import on first installation.

The automatic `GromozekaWorker` service runs as LocalSystem. Its command and file
tools therefore have system permissions, and use the service's environment and
credential stores, not those of the logged-in user. Standard users cannot stop
or reconfigure the service, modify the installation, or read its private data.
The service restarts after failure. Its credentials remain local to the service.

Computer Use runs in a supervised internal helper under the active console
user's account. This is still one registered Worker. The helper has no Worker
Gateway connection or credential. It is connected through a local, restricted
named pipe with process identity checks and is terminated if the service dies.
Helper restarts and user switches invalidate existing desktop observations;
desktop actions with an uncertain outcome are never retried automatically.

Computer Use becomes usable after a console user logs in and unlocks Windows.
The standalone screenshot tool also uses the helper and captures the complete
user desktop across all monitors. A missing or locked user desktop returns an
error instead of capturing the service's Session 0 desktop.
Lock screens, UAC secure desktops, and selecting arbitrary RDP sessions are not
supported by this helper. Shell commands run in service Session 0; use Computer
Use to launch and interact with visible applications in the user's desktop.
An ordinary interactive Worker launch still uses its desktop directly.

From a newly extracted distribution, run `bin\gromozeka-worker.cmd update-service`
as administrator to replace the installed program and bundled runtimes. Other
commands are `start-service`, `stop-service`, `service-status`, and
`uninstall-service`. Uninstall removes the service registration but preserves the
installed files and private configuration. Logs are under
`%ProgramData%\Gromozeka\Worker\logs\workers`.

The package includes pinned, checksum-verified Eclipse Temurin 21, Node.js and
CPython runtimes. Launchers and Code Mode use these private runtimes, do not
change system installations, and do not download executable code during startup.

## Windows Python conditions

Before the first Windows connection, configuration or manual Worker start, the
terminal displays Python's additional Windows redistribution conditions. Type
`yes` to accept; blank input, EOF or any other answer stops before configuration
or connection. A non-interactive process never assumes acceptance or reads a
piped bootstrap document as an answer.

After reviewing the bundled `runtime/python/LICENSE.txt`, unattended provisioning
can record acceptance explicitly, using the same `GROMOZEKA_HOME` as the Worker:

```powershell
bin\gromozeka-worker.cmd accept-licenses --yes
```

Without `--yes`, `accept-licenses` asks in an interactive terminal. Acceptance is
stored privately under `GROMOZEKA_HOME/licenses/windows-python`, keyed by the
conditions' content, not the Worker or Python version. Unchanged conditions do
not ask again. Older acknowledgements remain valid for rollback to an older
installation; genuinely changed conditions need a new agreement.

The Windows service installer validates and carries a matching acknowledgement
from the provisioning user's home into the service home. It checks conditions
before importing configuration, stopping the old service or replacing program
files. For unattended service setup, after reviewing the terms, use
`install-service --accept-licenses` (also available for `update-service` and
`start-service`). Automatic updates never add that flag. An existing installation
with no matching acknowledgement needs manual confirmation before its first
update to this version. Normal service restarts and script calls show no dialogs.

## Python Code Mode

`grz_execute_python` runs inline code or an existing `.py` file in a separate
managed process, using the bundled Python, async `grz` SDK and HTTPX. It accepts
ordinary scripts and top-level `await`. Discover exact MCP tool names and schemas
with `await client.list_tools()` and `await client.describe_tool(name)`; use
`await client.call(name, arguments)` sequentially or `asyncio.gather` for
independent calls. `async with grz.Client.from_env() as client` connects using
the launching user's and Agent's permissions without manual connection setup.

Output, timeouts, cancellation and background completion use the same command
tasks as shell execution. Cancellation stops the process tree, but cannot undo
completed tool calls or guarantee that a remote side effect stopped. Failed or
interrupted MCP calls are never retried automatically. This is not a sandbox.

There is no per-script virtual environment or package installation. The stock
Python and its packages are updated with the Worker; active managed commands
block automatic Worker replacement. Custom interpreters, extra packages and
independent runtime updates are not supported by Code Mode. The package lock
and bundled license texts are under `runtime/python/legal`; package licenses
are also retained in their original `*.dist-info` directories.

The Worker connects outbound to the Server over WSS. It does not receive
PostgreSQL credentials and does not share the Server data directory. Remote
plaintext connections are rejected.

Claude Code is not bundled with Gromozeka. Install, license, and authenticate it
separately on this Worker before enabling a Claude Code connection.

The standalone Worker includes Gromozeka Browser MCP and its Node.js runtime.
Browser binaries are not bundled; Browser Use connects to the separately
installed Browser Bridge.
