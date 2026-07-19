# Testing Strategy

Gromozeka test suites are classified by the boundary they verify, not by the Gradle module that contains them. A test under an `infrastructure-*` module is not necessarily an infrastructure integration test.

## Test Categories

### Local deterministic tests

These tests do not require network access, external services, or real model calls.

- **Unit tests** verify one policy, mapper, parser, validator, state transition, or algorithm in isolation.
- **Component tests** connect several production components in-process, usually with fake LLM runtimes, in-memory stores, and in-memory queues. Examples include memory pipelines and the conversation runtime dispatcher.
- **Adapter fixture tests** verify request serialization and response parsing against fixed payloads. They protect Gromozeka from its own adapter regressions, but do not prove that an external provider still follows the captured contract.

### Infrastructure integration tests

These tests exercise a real external component such as PostgreSQL or RabbitMQ. They may use the local machine or Docker but should not require a public internet connection.

The RabbitMQ runtime queue test is currently opt-in through `GROMOZEKA_RABBIT_RUNTIME_TEST=true`. PostgreSQL is exercised mainly by memory E2E rather than by a comprehensive repository-level integration suite.

### Live provider conformance tests

These are small network tests against a real provider. They verify that the currently deployed OpenAI, Anthropic, or another external API still accepts our requests and produces events that our adapter understands.

This is distinct from adapter fixture testing. At present, live conformance is mostly covered indirectly when an E2E suite runs in `record-missing` or `refresh` mode; it is not a clearly separated suite yet.

### Memory scenario E2E

The real-model memory suite runs 62 Gromozeka-specific scenarios through the server/application boundary, conversation runtime, PostgreSQL-backed memory, and the configured LLM runtime.

E2E describes the tested system boundary, not whether the run uses the public network. A replay-only E2E run remains an E2E run even though all LLM responses come from local cassettes.

### LongMemEval

LongMemEval is an external 500-case memory benchmark. It exercises ingest, recall, answer generation, and LLM judging. It is an evaluation workload rather than an ordinary regression suite, although recorded runs can later be replayed deterministically.

### Manual UI acceptance smoke

Wasm, JVM, and iOS flows are currently checked manually against a running server. The Playwright procedure in `AGENTS.md` makes browser checks reproducible, but there is no automated UI E2E suite with named scenarios, assertions, and reports yet.

### Build and platform checks

JVM, Wasm, and iOS compilation checks are quality gates, but they are not behavioral tests.

## LLM Cassette Use

Not every test uses cassettes.

| Test category | Cassette use | Network behavior |
| --- | --- | --- |
| Unit/component | No | No network |
| Adapter fixture | No, except tests of the cassette implementation itself | No network |
| Infrastructure integration | No | Local service access only |
| Live provider conformance | Normally no | Real provider network call |
| Memory scenario E2E | Yes | Depends on cassette mode |
| LongMemEval | Yes | Depends on cassette mode |
| Manual UI smoke | No test-level cassette by default | Depends on the running server configuration |

Cassette modes:

- `replay-only`: requires an exact recorded request hash and never calls the provider.
- `record-missing`: replays known requests and calls the provider only for misses.
- `refresh`: calls the provider and overwrites matching recordings.
- `off`: calls the provider without cassette recording or replay.

Cassettes cache only LLM calls. They do not cache PostgreSQL, RabbitMQ, WebSocket, MCP, or UI behavior.

## Current Gaps

- No automated browser/mobile UI E2E suite.
- No dedicated live provider conformance suite.
- PostgreSQL repository integration coverage is not yet comprehensive.
- RabbitMQ integration coverage is opt-in and narrow.
- No dedicated load, performance, chaos, or distributed-worker E2E suite.
