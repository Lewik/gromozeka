# Agent tool access

Tool access is part of the reusable Agent definition, not a Telegram route or a
provider connection. Existing Agents default to an empty denylist, preserving
their previous permissions. User, project, Worker and integration access remain
additional constraints; an Agent policy cannot grant those resources.

`ToolAccessPolicy` has two explicit variants:

- `AllowOnly`: only matching tools are permitted. Empty means no tools.
- `DenyListed`: matching tools are excluded. Empty adds no restriction.

Entries combine by OR. Each `ToolSelector` is either an `ExactRevision` containing
the existing full SHA-256 contract fingerprint, or `ByName` containing both the
source and logical name. Name-wide entries intentionally include future revisions
of that exact qualified identity; unrelated new tools remain denied in an allowlist.
The fingerprint algorithm is unchanged: descriptions, schemas, source and runtime
metadata participate. It identifies the advertised contract, not executable code.

Use the existing Agent editor's **Tool access** dialog to choose the mode, add a
registered revision, or switch an entry to **All revisions**. The catalog shows
the source, logical name, actual model-facing name, registry variant and short hash.
An unavailable exact revision is retained; it never follows a replacement silently.
Registry variant numbers are local identities, not portable software versions.

`AgentPreloadedTools` is a separate typed setting (`tools: {"names": [...]}`).
Preloading never grants access. Conflicting preloads are saved unchanged, with a
non-blocking warning when the current catalog can identify them. Runtime selection
filters denied entries; granting access again restores their effective preload.
Unavailable catalogs do not prevent saving, and runtime checks stay authoritative.

The policy filters the capability catalog, core tools, configured preloads,
previously discovered tools, `search_tools` results, skill tool availability and
external tools supplied to memory pipelines. Routing rejects unexposed calls. A
queued tool task rechecks the current Agent policy and currently available contract
before dispatch. Revocation does not undo an already-started action or revoke a
request that has already been dispatched to a Worker.

OpenAI API and Subscription hosted web search are separate provider-native catalog
entries. Both require the connection flag and Agent permission. Their fingerprints
describe Gromozeka's adapter contract, not a pinned implementation inside OpenAI.
Disallowing native search removes it from that request without changing the shared
connection. See [OpenAI web search](https://developers.openai.com/api/docs/guides/tools-web-search).

The existing control MCP exposes `grz_agent_tool_catalog` and typed `toolAccess`
on `grz_agent_create` / `grz_agent_update`. Model-originated create/update/duplicate
operations cannot grant a policy broader than the calling Agent's policy. A human
owner can change permissions through the UI or an independently authenticated
control client. This is not an argument-level sandbox: granting shell, administrative,
delegation or unrestricted browser tools still grants their consequential behavior.

## Restricted Telegram setup

Create a dedicated project without Workspaces, mounts or Worker grants. Give its
Agent an allowlist containing only the approved exact revisions of
`brave_web_search` and `jina_read_url`, and preload those two. Do not include
discovery, files, shell, private memory, secrets, delegation or administrative tools.
Channel conversations already disable automatic memory and suggested replies.

The two standard web integrations require explicitly enabled Brave/Jina settings
and their configured keys. Neither uses personal browser cookies. Brave receives
the query and Jina receives the URL; they do not receive the conversation from
the tool implementation. The Agent should send minimal public queries, not group
history. A query or URL can still contain private text supplied by the model.

Both requests have a 60-second timeout and a 2 MiB response-body limit. Payloads
are omitted from their operational logs. Jina accepts HTTP(S) public-web URLs on
standard ports, rejects embedded credentials, obvious local hostnames and private
IP literals, and delegates fetching to Jina rather than fetching on the Server.
The local validation does not claim control over Jina's DNS or redirect handling.
Brave query/count/page limits follow its
[API contract](https://api-dashboard.search.brave.com/api-reference/web/search/get).

## Verification

Run focused policy, discovery, skill, memory-tool, request-mapper, gateway codec,
execution-guard and control-authority tests. `PostgresAgentToolAccessTest` is opt-in
with `GROMOZEKA_POSTGRES_RUNTIME_TEST=true` and `GROMOZEKA_POSTGRES_URL`; it verifies
upgrading an old preload value and round-tripping both policies in a fresh schema.
The opt-in Telegram browser fixture includes non-executable web/file descriptors
for visually checking the editor and saving a preload conflict. It never calls
those tools or sends Telegram messages.
