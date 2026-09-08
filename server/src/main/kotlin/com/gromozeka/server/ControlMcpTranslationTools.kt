package com.gromozeka.server

import com.gromozeka.domain.model.TranslationMetadata
import com.gromozeka.domain.model.TranslationSnapshot
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.UserTranslationService
import com.gromozeka.shared.localization.BundledTranslations
import com.gromozeka.shared.localization.CardinalPluralRules
import com.gromozeka.shared.localization.PluralCategory
import com.gromozeka.shared.localization.TranslationJson
import com.gromozeka.shared.localization.TranslationMessageSerializer
import com.gromozeka.shared.localization.TranslationPackage
import com.gromozeka.shared.localization.TranslationSource
import com.gromozeka.shared.localization.TranslationValidationIssue
import com.gromozeka.shared.localization.TranslationValidator
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class ControlMcpTranslationTools(
    private val translationService: UserTranslationService,
    private val clientPresentationRegistry: ClientPresentationRegistry,
) : ControlMcpToolProvider {
    override val tools: List<ControlMcpTool> = listOf(
        controlMcpTool(
            name = "grz_translation_list",
            description = "List your bundled and personal UI translations, revision, common/per-client choices and known client IDs. Omit clientId for the common selection; pass one of your listed IDs for that client's selection. Package bodies are available through grz_translation_get. No Project or Server owner permission is required.",
            inputSchema = ControlMcpSchemas.objectSchema(mapOf("clientId" to translationClientSchema())),
            readOnly = true,
        ) { input ->
            input.requireTranslationFields("clientId")
            val clientId = approvedClientId(user.id, input)
            snapshotResult(user.id, translationService.snapshot(user.id, clientId))
        },
        controlMcpTool(
            name = "grz_translation_source",
            description = "Read paginated English UI messages with their exact semantic context and translation guide. Defaults to 100 messages, with glossary and translator prompt on the first page. Use keys or keyPrefix to focus work and nextOffset to continue the same filters. Source pages are fragments: a new language must translate every source key and pass full validation, without filling omissions with English. targetLocale returns its CLDR plural requirements.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "keys" to ControlMcpSchemas.stringArray("Optional exact source message IDs. May be combined with keyPrefix as an intersection."),
                    "keyPrefix" to ControlMcpSchemas.string("Optional message ID prefix, for example agents. or settings. See availablePrefixes."),
                    "offset" to ControlMcpSchemas.integer("Zero-based offset within the filtered source. Defaults to 0.", minimum = 0),
                    "limit" to ControlMcpSchemas.integer("Number of messages in this page. Defaults to 100.", minimum = 1),
                    "includeGuide" to ControlMcpSchemas.boolean("Include the glossary and translator prompt. Defaults to true at offset 0, false on subsequent pages."),
                    "targetLocale" to ControlMcpSchemas.string("Optional BCP 47 target locale, including custom languages beyond the bundled locales."),
                ),
            ),
            readOnly = true,
        ) { input ->
            input.requireTranslationFields("keys", "keyPrefix", "offset", "limit", "includeGuide", "targetLocale")
            sourcePage(input)
        },
        controlMcpTool(
            name = "grz_translation_get",
            description = "Read one complete bundled or personal translation package by its exact selectionId. Personal packages are visible only to their owner. Use grz_translation_list for IDs and the latest revision; use grz_translation_source for translation context and guidance.",
            inputSchema = ControlMcpSchemas.objectSchema(
                mapOf("selectionId" to translationSelectionSchema()),
                required = listOf("selectionId"),
            ),
            readOnly = true,
        ) { input ->
            input.requireTranslationFields("selectionId")
            val selectionId = input.requiredString("selectionId")
            val translation = translationService.getPackage(user.id, selectionId)
            buildJsonObject {
                put("selectionId", selectionId)
                put("translation", translation.toTranslationJson())
            }
        },
        controlMcpTool(
            name = "grz_translation_validate",
            description = "Validate a complete translation without saving it. Supply exactly one of translation (an object) or json (the raw JSON string, checked for duplicate keys). Returns valid and structured issues, including parse failures. All source keys, text/plural shapes, locale plural categories, placeholders and metadata must be valid.",
            inputSchema = translationPackageInputSchema(mutation = false),
            readOnly = true,
        ) { input ->
            val issues = try {
                input.requireTranslationFields("translation", "json")
                TranslationValidator.validate(input.decodeTranslationPackage())
            } catch (error: IllegalArgumentException) {
                listOf(TranslationValidationIssue(null, "parse_error", error.message ?: "Invalid translation JSON"))
            }
            buildJsonObject {
                put("valid", issues.isEmpty())
                put("issues", JsonArray(issues.map {
                    controlMcpJson.encodeToJsonElement(TranslationValidationIssue.serializer(), it)
                }))
            }
        },
        controlMcpTool(
            name = "grz_translation_save",
            description = "Save a complete validated personal translation using the latest expectedRevision. Supply exactly one of translation or raw json. Omit packageId to create personal:<UUID>; pass an existing personal ID to replace it. Bundled packages cannot be overwritten. For a new language translate all keys, without English fallback for missing work. Saving does not select the language; use grz_translation_select explicitly.",
            inputSchema = translationPackageInputSchema(mutation = true),
            readOnly = false,
        ) { input ->
            input.requireTranslationFields("translation", "json", "expectedRevision", "packageId", "clientId")
            val result = translationService.savePackage(
                userId = user.id,
                translation = input.decodeTranslationPackage(),
                expectedRevision = input.requiredLong("expectedRevision"),
                packageId = input.optionalTranslationString("packageId"),
                clientInstanceId = approvedClientId(user.id, input),
            )
            snapshotResult(user.id, result.snapshot, mapOf("packageId" to JsonPrimitive(result.packageId)))
        },
        controlMcpTool(
            name = "grz_translation_customize",
            description = "Create a personal copy of an existing translation with a custom name and selected message replacements, for example changing one UI term. sourceSelectionId may be bundled or personal. The source locale and direction stay unchanged; omitted messages are copied from that source. This is not a way to create a new language with English fallback. Every replacement must use a known key and preserve its text/plural shape and arguments. Requires expectedRevision and does not select the copy. Use grz_translation_save to update an existing personal package.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "sourceSelectionId" to translationSelectionSchema(),
                    "name" to ControlMcpSchemas.string("Name of the new personal translation; its locale and direction are inherited from the source."),
                    "messages" to ControlMcpSchemas.objectValue("Partial map of known message IDs to replacement strings or complete plural objects. Read grz_translation_source for each key's semantic context."),
                    "expectedRevision" to translationRevisionSchema(),
                    "clientId" to translationClientSchema(),
                ),
                required = listOf("sourceSelectionId", "name", "messages", "expectedRevision"),
            ),
            readOnly = false,
        ) { input ->
            input.requireTranslationFields("sourceSelectionId", "name", "messages", "expectedRevision", "clientId")
            val source = translationService.getPackage(user.id, input.requiredString("sourceSelectionId"))
            val replacements = input.requiredObject("messages")
            val unknownKeys = replacements.keys - source.messages.keys
            require(unknownKeys.isEmpty()) { "Unknown translation message IDs: ${unknownKeys.sorted().joinToString()}" }
            val messages = replacements.mapValues { (_, value) ->
                TranslationJson.format.decodeFromJsonElement(TranslationMessageSerializer, value)
            }
            val result = translationService.savePackage(
                userId = user.id,
                translation = source.copy(name = input.requiredString("name"), messages = source.messages + messages),
                expectedRevision = input.requiredLong("expectedRevision"),
                clientInstanceId = approvedClientId(user.id, input),
            )
            snapshotResult(user.id, result.snapshot, mapOf("packageId" to JsonPrimitive(result.packageId)))
        },
        controlMcpTool(
            name = "grz_translation_delete",
            description = "Delete your personal translation using expectedRevision. Bundled translations cannot be deleted. Every common or per-client choice referencing the deleted package is reset to builtin:en.",
            inputSchema = translationSelectionInputSchema(),
            readOnly = false,
            destructive = true,
        ) { input ->
            input.requireTranslationFields("selectionId", "expectedRevision", "clientId")
            val selectionId = input.requiredString("selectionId")
            val snapshot = translationService.deletePackage(
                userId = user.id,
                selectionId = selectionId,
                expectedRevision = input.requiredLong("expectedRevision"),
                clientInstanceId = approvedClientId(user.id, input),
            )
            snapshotResult(user.id, snapshot, mapOf("deleted" to JsonPrimitive(true), "packageId" to JsonPrimitive(selectionId)))
        },
        controlMcpTool(
            name = "grz_translation_select",
            description = "Explicitly select one available translation using expectedRevision. With synchronization enabled, changes the common choice for your clients. With synchronization disabled, requires an exact clientId from grz_translation_list and changes only that client's choice. MCP has no implicit current client; never guess its ID.",
            inputSchema = translationSelectionInputSchema(),
            readOnly = false,
        ) { input ->
            input.requireTranslationFields("selectionId", "expectedRevision", "clientId")
            val snapshot = translationService.select(
                userId = user.id,
                selectionId = input.requiredString("selectionId"),
                expectedRevision = input.requiredLong("expectedRevision"),
                clientInstanceId = approvedClientId(user.id, input),
            )
            snapshotResult(user.id, snapshot)
        },
        controlMcpTool(
            name = "grz_translation_synchronize",
            description = "Enable or disable synchronized language selection for your clients using expectedRevision. Enabling adopts the specified client's effective selection as the common choice and clears per-client overrides; clientId is required if overrides exist. Disabling retains the common choice as fallback and starts with no overrides. Omit clientId only when no per-client choice must be adopted; never invent a client ID.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "synchronizeClients" to ControlMcpSchemas.boolean("Whether your clients should use one common translation selection."),
                    "expectedRevision" to translationRevisionSchema(),
                    "clientId" to translationClientSchema(),
                ),
                required = listOf("synchronizeClients", "expectedRevision"),
            ),
            readOnly = false,
        ) { input ->
            input.requireTranslationFields("synchronizeClients", "expectedRevision", "clientId")
            require("synchronizeClients" in input) { "'synchronizeClients' is required" }
            val snapshot = translationService.setSynchronizeClients(
                userId = user.id,
                synchronizeClients = input.optionalBoolean("synchronizeClients", false),
                expectedRevision = input.requiredLong("expectedRevision"),
                clientInstanceId = approvedClientId(user.id, input),
            )
            snapshotResult(user.id, snapshot)
        },
    )

    private suspend fun approvedClientId(userId: User.Id, input: JsonObject): String? {
        val clientId = input.optionalTranslationString("clientId") ?: return null
        val snapshot = translationService.snapshot(userId)
        val knownIds = clientPresentationRegistry.registeredClientIds(userId) + snapshot.clientSelections.keys
        require(clientId in knownIds) {
            "Unknown client ID for your account: $clientId. Read grz_translation_list and specify one of your client IDs."
        }
        return clientId
    }

    private suspend fun snapshotResult(
        userId: User.Id,
        snapshot: TranslationSnapshot,
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject {
        val registeredIds = clientPresentationRegistry.registeredClientIds(userId)
        val knownIds = (registeredIds + snapshot.clientSelections.keys).sorted()
        return buildJsonObject {
            put("revision", snapshot.revision)
            put("available", JsonArray(snapshot.available.map {
                controlMcpJson.encodeToJsonElement(TranslationMetadata.serializer(), it)
            }))
            put("synchronizeClients", snapshot.synchronizeClients)
            put("commonSelectionId", snapshot.commonSelectionId)
            put("effectiveSelectionId", snapshot.effectiveSelectionId)
            put("clientSelections", JsonObject(snapshot.clientSelections.mapValues { JsonPrimitive(it.value) }))
            put("clientIds", JsonArray(knownIds.map(::JsonPrimitive)))
            put("clients", JsonArray(knownIds.map { clientId ->
                buildJsonObject {
                    put("clientId", clientId)
                    put("registered", clientId in registeredIds)
                    put("selectionId", if (snapshot.synchronizeClients) snapshot.commonSelectionId
                        else snapshot.clientSelections[clientId] ?: snapshot.commonSelectionId)
                }
            }))
            extra.forEach { (key, value) -> put(key, value) }
        }
    }
}

private fun sourcePage(input: JsonObject): JsonObject {
    val source = BundledTranslations.english
    val requestedKeys = if ("keys" in input) input.requiredStringList("keys").toSet() else null
    val unknownKeys = requestedKeys.orEmpty() - source.messages.keys
    require(unknownKeys.isEmpty()) { "Unknown source message IDs: ${unknownKeys.sorted().joinToString()}" }
    val prefix = input.optionalTranslationString("keyPrefix")
    val offset = input.optionalInt("offset", 0, 0..Int.MAX_VALUE)
    val limit = input.optionalInt("limit", 100, 1..Int.MAX_VALUE)
    val includeGuide = input.optionalBoolean("includeGuide", offset == 0)
    val targetLocale = input.optionalTranslationString("targetLocale")
    require(targetLocale == null || Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*").matches(targetLocale)) {
        "'targetLocale' must be a BCP 47 language tag"
    }
    val filteredKeys = source.messages.keys.filter { key ->
        (requestedKeys == null || key in requestedKeys) && (prefix == null || key.startsWith(prefix))
    }.sorted()
    val pageKeys = filteredKeys.drop(offset).take(limit)
    val nextOffset = (offset + pageKeys.size).takeIf { it < filteredKeys.size }
    return buildJsonObject {
        put("source", source.copy(messages = pageKeys.associateWith(source.messages::getValue)).toTranslationJson())
        put("context", JsonObject(pageKeys.associateWith(TranslationSource.context::getValue)))
        put("total", filteredKeys.size)
        put("sourceTotal", source.messages.size)
        put("offset", offset)
        put("limit", limit)
        put("returned", pageKeys.size)
        put("nextOffset", nextOffset?.let(::JsonPrimitive) ?: JsonNull)
        put("completeSource", pageKeys.size == source.messages.size)
        put("availablePrefixes", JsonArray(source.messages.keys.map {
            if ('.' in it) it.substringBefore('.') + "." else it
        }.distinct().sorted().map(::JsonPrimitive)))
        if (targetLocale != null) {
            val categories = PluralCategory.entries.filter { it in CardinalPluralRules.categories(targetLocale) }
            put("targetLocale", targetLocale)
            put("pluralCategories", JsonArray(categories.map { JsonPrimitive(it.name) }))
            put("implicitCountCategories", JsonArray(categories.filter {
                CardinalPluralRules.allowsImplicitCount(targetLocale, it)
            }.map { JsonPrimitive(it.name) }))
        }
        if (includeGuide) {
            put("guide", buildJsonObject {
                put("translatorPrompt", TranslationSource.translatorPrompt)
                put("glossary", TranslationSource.glossary)
            })
        }
    }
}

private fun JsonObject.decodeTranslationPackage(): TranslationPackage {
    require(("translation" in this) xor ("json" in this)) { "Supply exactly one of 'translation' or 'json'" }
    val value = if ("translation" in this) {
        requiredObject("translation").toString()
    } else {
        (get("json") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ControlMcpToolException("invalid_argument", "'json' must be a raw JSON string")
    }
    return TranslationJson.decode(value)
}

private fun TranslationPackage.toTranslationJson(): JsonElement =
    TranslationJson.format.encodeToJsonElement(TranslationPackage.serializer(), this)

private fun JsonObject.optionalTranslationString(name: String): String? =
    if (name in this) requiredString(name) else null

private fun JsonObject.requireTranslationFields(vararg allowed: String) {
    val unknown = keys - allowed.toSet()
    require(unknown.isEmpty()) { "Unknown translation tool arguments: ${unknown.sorted().joinToString()}" }
}

private fun translationClientSchema(): JsonObject = ControlMcpSchemas.string(
    "Optional exact client ID from grz_translation_list. Omit for the common selection; MCP has no implicit current client.",
)

private fun translationSelectionSchema(): JsonObject = ControlMcpSchemas.string(
    "Exact selection ID from grz_translation_list: builtin:<locale> or personal:<UUID>.",
)

private fun translationRevisionSchema(): JsonObject = ControlMcpSchemas.integer(
    "Latest user translation revision from grz_translation_list or the previous mutation result. Refresh after a conflict.",
    minimum = 0,
)

private fun translationSelectionInputSchema(): ToolSchema = ControlMcpSchemas.objectSchema(
    properties = mapOf(
        "selectionId" to translationSelectionSchema(),
        "expectedRevision" to translationRevisionSchema(),
        "clientId" to translationClientSchema(),
    ),
    required = listOf("selectionId", "expectedRevision"),
)

private fun translationPackageInputSchema(mutation: Boolean): ToolSchema = ControlMcpSchemas.objectSchema(
    properties = mapOf(
        "translation" to ControlMcpSchemas.objectValue(
            "Complete package object: schemaVersion=1, locale, name, direction (LTR/RTL), messages containing every source ID with matching text/plural shape. Supply exactly one of translation or json.",
        ),
        "json" to ControlMcpSchemas.string("Alternative raw package JSON string, including duplicate-key validation. Supply exactly one of translation or json."),
    ) + if (mutation) mapOf(
        "packageId" to ControlMcpSchemas.string("Existing personal:<UUID> to replace; omit to create a new personal package."),
        "expectedRevision" to translationRevisionSchema(),
        "clientId" to translationClientSchema(),
    ) else emptyMap(),
    required = if (mutation) listOf("expectedRevision") else emptyList(),
)
