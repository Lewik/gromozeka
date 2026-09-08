# Interface translations

`en.json` and `context.json` are the only canonical message source and semantic
context. The other root-level locale JSON files are complete translations.
Message IDs describe stable meanings independently of their wording.

## Packages and translation context

A package is ordinary JSON with exactly `schemaVersion` (integer `1`), `locale`
(BCP 47), `name` (autonym or the user's chosen name), `direction` (`LTR` or `RTL`),
and `messages`. A message is a string or an object of CLDR cardinal plural forms,
including `other`. Personal packages must contain every current English source
key, with matching message shapes and arguments; missing work is not filled with
English. Custom locales and directions are supported beyond the bundled list.
`TranslationJson.decode` rejects duplicate keys, unknown package fields and wrong
JSON value types. CBOR transport preserves the same typed package; export/import
JSON keeps the simple string/plural-object representation.

Named arguments use `{name}` and are inserted once as data. Preserve argument
names and occurrences, even when changing word order. Plural `count` is supplied
separately by the renderer. A `zero`, `one` or `two` form may omit `{count}` only
when its CLDR category selects exactly that integer and the wording expresses it
unambiguously. Russian `one` and French/Portuguese `one` do not qualify. Existing
printf messages retain argument types, precision and occurrences; use `%2$s`,
`%1$d`, etc. to reorder them and `%%` for a literal percent. Dynamic arguments are
isolated by the renderer in RTL translations; do not insert bidi controls into
catalog strings. Preserve technical identifiers, commands, URLs, paths and user
content while translating their surrounding explanation.

Translation guidance is separate from runtime packages:

- `context.json`: per-key meaning, source anchors, argument names and, where
  useful, argument descriptions/examples and disambiguation.
- `glossary.json`: conceptual definitions and terminology policy. User wording
  preferences may override bundled terminology.
- `translator-prompt.md`: generation and language-review instructions.

`TranslationSource` exposes this guidance to MCP. `TranslationCatalog.text`,
`plural` and `format` render named, plural and legacy printf messages. Compose
uses `LocalTranslation.current` and the presentation `Translation` facade. Pass
the active translation into non-Compose helpers; language-dependent text must not
be frozen in a `remember` value without a translation dependency.

Bundled locales: `en`, `ru`, `he`, `es`, `pt-BR`, `ja`, `zh-Hans`, `zh-Hant`,
`de`, `fr`, `ko`, `ar`, `id`.

## Ownership, selection and client state

The Server owns each authenticated User's personal packages and selection
preferences. There is no Project restriction, Server owner requirement or product
limit on personal languages. Transport supplies the authenticated `User.Id`;
caller-provided user IDs cannot select another user's storage.

Bundled IDs are immutable `builtin:<locale>`. Creating a personal package assigns
`personal:<UUID>`; replacing one requires its existing personal ID. Names and
locales do not determine identity. The contract lives in
[`UserTranslationService`](../domain/src/commonMain/kotlin/com/gromozeka/domain/service/UserTranslationService.kt),
[`TranslationState`](../domain/src/commonMain/kotlin/com/gromozeka/domain/model/TranslationState.kt)
and [`TranslationRepository`](../domain/src/commonMain/kotlin/com/gromozeka/domain/repository/TranslationRepository.kt).

The default is revision `0`, synchronized selection and `builtin:en`. While
synchronized, all clients use `commonSelectionId`. Otherwise each stable client
instance ID can override the common fallback. Disabling synchronization starts
with no overrides. Enabling it adopts the specified client's effective choice as
the common choice and clears overrides. A repeated request for the current sync
mode retains the current choices. Deleting a personal package replaces every
reference to it with `builtin:en`.

All mutations require `expectedRevision` and advance the per-user revision,
including repeated settings requests. The application mutex and database
compare-and-set reject stale writes with `TranslationRevisionConflictException`;
read a fresh snapshot and reconsider the edit after a conflict. PostgreSQL stores
one complete user state in `translation_states` (migration V54). After commit,
`DeclarativeStateKey.translations(userId)` invalidates that user's client state.
Snapshots carry all package metadata and only the selected package body;
`getPackage` reads another body explicitly.

The client persists its accepted snapshot under normalized Server URL plus User
ID. A valid cached selected package can render immediately while disconnected;
without one, bootstrap uses a matching bundled locale from the saved bootstrap
preference or device language. Server snapshots remain authoritative for
revisions and changes. Cache contents are validated, older incoming revisions
are ignored, and reconnect reloads through state sync. This is a display cache,
not an offline mutation queue. Native OS resources and the pre-login/web loader
use generated bundled translations rather than rewriting resources from a
personal package.

Service/MCP saves do not activate a package. The Settings import action
explicitly performs save followed by select as two revisioned operations.

## MCP tools

All nine tools are available to authenticated users and are also exposed through
the existing conversation-tool bridge. List and mutation results are compact
snapshots with metadata, revisions and client choices, without message bodies.

| Tool | Inputs and behavior |
| --- | --- |
| `grz_translation_list` | Current revision, available packages and known client IDs. Optional `clientId`; omission reports the common selection. |
| `grz_translation_source` | English messages plus their context. Optional `keys`, `keyPrefix`, `offset`, `limit`, `includeGuide`, `targetLocale`. |
| `grz_translation_get` | Complete package for `selectionId`. |
| `grz_translation_validate` | Exactly one of `translation` object or raw `json` string. Returns `valid` and structured `issues`, including parse errors. |
| `grz_translation_save` | Same package input plus `expectedRevision`; omit `packageId` to create, supply it to replace a personal package. Returns its ID; does not select it. |
| `grz_translation_customize` | `sourceSelectionId`, `name`, partial `messages`, `expectedRevision`. Creates a new personal copy in the source locale/direction, validates replacements and leaves selection unchanged. |
| `grz_translation_delete` | Personal `selectionId` and `expectedRevision`; destructive, with deterministic English fallback for affected choices. |
| `grz_translation_select` | `selectionId`, `expectedRevision`, optional `clientId`; explicitly changes the common or specified client's choice. |
| `grz_translation_synchronize` | `synchronizeClients`, `expectedRevision`, optional `clientId`; changes synchronization mode. |

`source` defaults to 100 sorted keys. `keys` and `keyPrefix` intersect; `offset`
addresses that filtered set. Use `total`, `sourceTotal`, `nextOffset` and
`availablePrefixes` to navigate. Context covers only the returned keys. The
`guide` (glossary and translator prompt) defaults on at offset 0 and off afterward;
`includeGuide` overrides this. `targetLocale` returns `pluralCategories` and
`implicitCountCategories`. Pages are source fragments, not valid full packages.

MCP has no implicit current client. Explicit `clientId` values must belong to the
caller: current registrations plus IDs with stored per-client choices, as shown
by `list`. Selecting with synchronization off requires a client ID; enabling
synchronization with existing overrides also requires one. Save, customize and
delete accept an optional client ID for their result's effective selection, but
do not require one. Never guess a client ID.

## Updating source and coverage

1. Update `en.json` and the matching `context.json` entries directly. Keep IDs for
   wording changes; update every locale for added/changed meanings or arguments,
   and remove deleted keys from every catalog and context. Add useful conceptual
   guidance to the glossary without copying it into each package.
2. Review complete translated messages for meaning, naturalness and terminology.
   Run coverage/argument checks and inspect the unchanged-English audit:

   ```bash
   python3 scripts/localization.py context
   python3 scripts/localization.py validate
   python3 scripts/localization.py audit
   ```

   `validate` and `audit` also accept locale arguments, for example `validate he ar`.
   The audit is an editorial report: unchanged product names can be correct.
3. Regenerate native outputs, then check that committed outputs match:

   ```bash
   python3 scripts/generate-native-localization.py
   python3 scripts/generate-native-localization.py --check
   ```

   This generates Android strings/locales configuration, iOS permission and
   shortcut resources, and the web bootstrap locale script. Edit the JSON source
   instead of these generated files. Adding a bundled locale also requires its
   registration in `scripts/localization.py`'s `LOCALES` for validation and native
   generation. Ordinary shared builds run `generateLocalizationBundle`
   automatically to embed catalogs, context/guidance and the CLDR license.
4. Check the affected UI/code path, including language switching and RTL when
   relevant, using the smallest affected compile and existing checks.

The localization command only checks existing catalogs; it does not regenerate
the English source. Make incremental edits directly in the canonical files and
review their diff before regenerating derived resources. For parallel work,
assign distinct message IDs and merge additions into the current files so that
another contributor's wording or context updates are retained.

## Plural data

`cldr/plurals.json` is Unicode CLDR 48 cardinal plural data, pinned to
`unicode-org/cldr-json` commit `4d06be52b51bb2f75688d0abe55c52a66afed790`.
Its source is the
[CLDR cardinal plural file](https://github.com/unicode-org/cldr-json/blob/4d06be52b51bb2f75688d0abe55c52a66afed790/cldr-json/cldr-core/supplemental/plurals.json).
`CardinalPluralRules` evaluates non-negative integer counts, matches locale tags
case-insensitively and falls back to the language subtag, then `other` for an
unknown language. Keep `cldr/LICENSE` (Unicode License V3) with redistributed
plural data; generated resources include its notice.
