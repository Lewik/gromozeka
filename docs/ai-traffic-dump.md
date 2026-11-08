# Dangerous AI traffic dump

Set one flag on the process executing AI calls:

```bash
GROMOZEKA_DANGEROUS_AI_TRAFFIC_DUMP=true
```

The default is off. The same flag covers every implemented model provider;
there are no provider-specific switches. Server-executed calls are captured on
the Server. Worker-executed calls are captured on that Worker, so configure the
flag there too when investigating a remote execution target. Restart the
relevant process after enabling or disabling it. AWS and distribution Compose
files pass the flag through with a default of `false`.

This deliberately records unredacted prompts, tools, results, images, opaque
reasoning, credentials present in protocol headers/bodies, and error payloads.
It does not decrypt encrypted reasoning. Use it temporarily and treat the
output as secret material. It has no truncation, rotation, automatic deletion,
or disk quota. It can consume substantial disk space and adds synchronous disk
I/O, so timings captured with it enabled include diagnostic overhead.

Files are written below `ai-traffic/` in the process's normal log directory
(`logging.file.path`, falling back to `GROMOZEKA_LOG_DIR`, then `logs`). Each HTTP
exchange, WebSocket connection, or CLI process gets a unique directory.
New recording directories/files use owner-only permissions on POSIX systems;
Windows uses the configured log directory's inherited access controls.

- `events.jsonl`: ordered timestamps, elapsed time, headers/status, lifecycle
  events, and byte offsets/lengths into the data files. HTTP failures and
  unparsable provider data are recorded before application parsing.
- `request.bin` / `response.bin`: HTTP body bytes, without text conversion.
- `send.text.bin` / `receive.text.bin`: complete WebSocket text messages in
  UTF-8, concatenated without added delimiters. Use `events.jsonl` offsets to
  recover individual messages. Fragment files preserve partial messages in
  UTF-16BE, including a stream that never finishes its final text message.
- Other WebSocket channels preserve binary/control payloads and close/error
  information. This is application-protocol capture, not a TLS packet capture;
  JDK-managed successful WebSocket handshake response headers are unavailable.
- `stdin.bin` / `stdout.bin` / `stderr.bin` and `system-prompt.bin`: Claude Code
  CLI protocol and launch input. Timestamps reflect when Gromozeka reads/writes
  the streams, not when the CLI's upstream model generates tokens.
- `rpc.bin`: complete Copilot SDK JSON-RPC send/receive records. This captures
  the Gromozeka-to-CLI boundary; it does not intercept the CLI's private HTTPS
  connection to GitHub.

OpenAI API and compatible inference, Anthropic API, and gateway model discovery
use the native HTTP transport capture. OpenAI's auxiliary SDK client and
Anthropic Bedrock use the SDK HTTP boundary (Bedrock's canonical Anthropic
request/response, before AWS transformation/signing). OpenAI Subscription
captures HTTP/SSE, WebSocket, authentication, quota and model-list traffic;
Claude quota HTTP is included too. Gemini is declared in the domain model but
has no runtime backend yet.

Raw HTTP bytes are recorded as they pass through the transport. Capturing does
not pre-read a one-shot request body or buffer a complete streaming response.
An I/O failure disables that recording and emits a warning without retrying or
failing the AI request. Partial files remain available for diagnosis. The
recorder never uploads data or writes payloads into ordinary application logs.

After debugging, disable the flag, restart the process, and remove the specific
recording directories when they are no longer needed.
