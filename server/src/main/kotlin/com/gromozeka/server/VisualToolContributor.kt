package com.gromozeka.server

import com.gromozeka.application.service.VisualApplicationService
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.visual.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service

@Service
class VisualToolContributor(
    private val visuals: VisualApplicationService,
    private val users: UserDirectoryService,
) : AiToolCallbackContributor {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override val callbacks = listOf(object : AiToolCallback {
        override val definition = AiToolDefinition("grz_visual", DESCRIPTION, SCHEMA)
        override val metadata = AiToolMetadata(
            executionScope = AiToolExecutionScope.SERVER,
            loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
            visibleToMemoryPipeline = false,
            logInput = false,
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
            try {
                val input = json.parseToJsonElement(toolInput) as? JsonObject ?: error("Expected an object")
                val action = input.string("action")
                val allowedArguments = when (action) {
                    "reference", "list" -> setOf("action")
                    "get", "close" -> setOf("action", "visual_id")
                    "create" -> setOf("action", "document", "state", "handler")
                    "update" -> setOf("action", "visual_id", "document", "state", "handler")
                    "highlight" -> setOf("action", "visual_id", "highlight")
                    else -> error("Unknown visual action")
                }
                require(input.keys.all { it in allowedArguments }) { "Unexpected $action arguments: ${input.keys - allowedArguments}" }
                val actor = users.findActiveById(context.requiredUserId()) ?: error("Active authenticated user required")
                if (action == "reference") return@runBlocking REFERENCE
                val conversationId = context?.getString(TOOL_CONTEXT_CONVERSATION_ID)?.takeIf(String::isNotBlank)
                    ?.let(Conversation::Id) ?: error("Visual tools require a conversation context")
                when (action) {
                    "create" -> {
                        val visual = visuals.create(actor, conversationId,
                            VisualCreate(input.string("document"), input["state"] as? JsonObject ?: error("state is required"), input.handler()),
                            context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)?.let(AgentDefinition::Id))
                        mutationResponse(visual)
                    }
                    "update" -> {
                        val visual = visuals.update(actor, conversationId, input.string("visual_id"),
                            VisualUpdate(document = input["document"]?.let { input.string("document") },
                                state = input["state"]?.let { it as? JsonObject ?: error("state must be an object") },
                                updateHandler = "handler" in input, handler = input.handler()))
                        mutationResponse(visual)
                    }
                    "get" -> documentResponse(visuals.list(actor, conversationId).singleOrNull { it.id == input.string("visual_id") }
                        ?: error("Visual not found in this conversation"))
                    "list" -> json.encodeToString(buildJsonObject {
                        put("visuals", buildJsonArray { visuals.list(actor, conversationId).forEach { visual ->
                            add(summary(visual))
                        } })
                    })
                    "highlight" -> {
                        val ids = input["highlight"] as? JsonArray ?: error("highlight must be an array of element IDs; [] clears it")
                        val targets = ids.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: error("Highlight IDs must be strings") }
                        val count = visuals.highlight(actor, conversationId, input.string("visual_id"), targets)
                        json.encodeToString(buildJsonObject {
                            put("success", count > 0)
                            put("sent_to_clients", count)
                            if (count == 0) put("error", "No connected client received this transient command. Open a client before requesting highlights.")
                        })
                    }
                    "close" -> {
                        visuals.close(actor, conversationId, input.string("visual_id"))
                        "{\"closed\":true}"
                    }
                    else -> error("Unknown visual action")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                json.encodeToString(buildJsonObject { put("success", false); put("error", (error.message ?: "Visual operation failed").take(1500)) })
            }
        }
    })

    private fun summary(visual: Visual): JsonObject = buildJsonObject {
        put("visual_id", visual.id)
        put("title", visual.title)
        put("status", visual.status.name)
        put("revision", visual.revision)
        put("document_revision", visual.documentRevision)
        put("form_revision", visual.formRevision)
        put("handler_task_id", visual.handler?.taskId?.value?.let(::JsonPrimitive) ?: JsonNull)
        put("diagnostic_count", visual.diagnostics.size)
    }

    /** Mutations acknowledge saved work without feeding the document/state back into model context. */
    private fun mutationResponse(visual: Visual): String = json.encodeToString(buildJsonObject {
        put("success", true)
        summary(visual).forEach { (name, value) -> put(name, value) }
        if (visual.diagnostics.isNotEmpty()) put("diagnostics", json.encodeToJsonElement(visual.diagnostics))
    })

    private fun documentResponse(visual: Visual): String = json.encodeToString(buildJsonObject {
        put("success", true)
        put("visual", JsonObject(json.encodeToJsonElement(visual).jsonObject - "formEventId"))
        put("palette", buildJsonObject { VisualDocumentCompiler.compile(visual.document).palette.forEach { (name, value) -> put(name, value) } })
    })

    private fun JsonObject.string(name: String): String = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("$name must be a string")

    private fun JsonObject.handler(): VisualHandlerSpec? {
        val value = get("handler") ?: return null
        if (value == JsonNull) return null
        val spec = value as? JsonObject ?: error("handler must be an object or null")
        require(spec.keys.all { it in setOf("workspace_mount_id", "command", "working_directory") }) { "Unknown handler argument" }
        return VisualHandlerSpec(WorkspaceMount.Id(spec.string("workspace_mount_id")), spec.string("command"),
            spec["working_directory"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
    }

    companion object {
        private const val PURPOSE = """Visual is a visual and interactive companion to the conversation and your shared work with the user. Proactively use panels to help the user understand what is being discussed, stay oriented in what is happening, and interact with it.
A panel can complement messages or present the same information in another useful form. Chat and Visual can work together; you do not need to choose between them.
Choose the content and structure of panels according to the current work. Panels may be informational, illustrative, or interactive. Keep them up to date as the conversation and task evolve."""

        const val DESCRIPTION = PURPOSE + "\n\n" + """Create, inspect, update or close native Visual tabs in this conversation's Runtime side panel.
Use action=reference before authoring a document: it returns the exact restricted HTML vocabulary and state protocol. Spacing is automatic; do not specify CSS or spacing attributes.
create requires document and state with both complete sections: {form:{...},data:{...}}. The document defines reusable structure and bindings. For subsequent content/value changes use update with state; send document again only when changing layout, controls or schema.
update accepts state containing one or both complete root sections, form and data. Each supplied section replaces the entire previous section, including all nested objects and arrays. Include every member you want to keep. An omitted section stays unchanged; omitted members inside a supplied section are removed. An empty section {} clears that section if the document schema permits it. The resulting full state must satisfy the schema and all bindings.
Prefer updating data only. Include form only to deliberately set/reset the entire form: it replaces all saved inputs and local drafts. A button submits the full {form,data} snapshot; only form is saved, stale input data is never written back. Without a script, clicks arrive as typed user interactions for this agent.
Optional handler starts an owned command on an exact workspace_mount_id. Its stdout/stderr must contain only NDJSON section updates: one object containing complete data and/or form, at most 8192 UTF-8 bytes per line. Omitted handler leaves the current handler unchanged; handler=null stops it and switches to LLM handling; an object replaces its process. close deletes the Visual and terminates its handler. No automatic restart or input replay.
create/update return compact acknowledgements, not the document or state. Only get returns the full HTML document, saved state, diagnostics and effective palette; list returns summaries. You author the document: reuse your last known markup, and use get to recover it when it has left your context, not to recheck your own unchanged work. Scripts and user input do not edit the document.
highlight accepts highlight=[element-id,...] (maximum 16), or [] to clear. It replaces the acting user's connected clients' local highlighted set without changing document, state or reminders. Clicking an arrow badge dismisses the set locally without activating the underlying control. No offline replay or dismissal feedback. Mutations require write access; reference/get/list are read-only. Do not retry create or uncertain button delivery automatically; inspect state instead."""
        const val SCHEMA = """{
            "type":"object",
            "properties":{
                "action":{"type":"string","enum":["reference","create","get","list","update","close","highlight"]},
                "visual_id":{"type":"string","description":"Existing Visual in this conversation; required for get, update, close and highlight."},
                "document":{"type":"string","description":"Required for create. On update, omit unless changing structure, controls or schema; use state for content and values."},
                "state":{"type":"object","description":"Create: both complete sections. Update: one or both complete sections. Each supplied section replaces its whole previous value; omitted sections stay unchanged, omitted nested members are removed.","properties":{"form":{"type":"object","description":"Complete saved input values. Include only to intentionally replace/reset the entire form, including client drafts."},"data":{"type":"object","description":"Complete display data. Include all data members to retain, including unchanged ones."}},"minProperties":1,"additionalProperties":false},
                "handler":{"type":["object","null"],"properties":{"workspace_mount_id":{"type":"string"},"command":{"type":"string"},"working_directory":{"type":["string","null"]}},"required":["workspace_mount_id","command"],"additionalProperties":false},
                "highlight":{"type":"array","items":{"type":"string"},"maxItems":16,"uniqueItems":true}
            },"required":["action"],"additionalProperties":false
        }"""
        val REFERENCE = PURPOSE + "\n\n" + """
            # Visual v1
            Native Compose rendering, not a browser. Restricted semantic HTML with well-formed XML syntax. No JavaScript, CSS, external resources, components, tables, or arbitrary attributes. Close empty tags: <input ... />. Boolean attributes require true/false or a boolean binding. All native names use kebab-case.

            Document: <html><head><title>Title</title><state-schema><![CDATA[JSON schema]]></state-schema></head><body>...</body></html>.
            The schema must declare and require exactly form and data, both objects. Supported schema keywords: type (object,array,string,number,integer,boolean,null; a type array is allowed), properties, required, items, enum, additionalProperties (boolean), title, description. Unknown keywords fail. The resulting full state is validated after every section replacement.
            Optional head palette: <palette><color name="warning" value="#B45309" /></palette>, 1..5 named colors. Default colors: red=#DC2626, green=#15803D, blue=#2563EB, orange=#B45309, gray=#6B7280. No light/dark variants in v1.

            Bindings: {data.total}, {form.query}, {data.rows[1].name}. Exact attribute bindings preserve JSON types. Text interpolation accepts scalars only, null becomes empty text. Escape literal braces with {{ and }}. No operators, calls or expressions. Missing paths are errors. Data strings are never parsed as markup.

            Common attributes: id (stable, at most 128 characters), hidden (boolean), title (hint), color (palette name for text).
            div: layout=column|row|grid (default column), border (boolean), background (palette name). No gap, padding, margin, or spacing attributes: the renderer owns spacing. Nested containers do not accumulate padding. Content receives consistent automatic spacing; backgrounds, borders and highlights do not change layout. Adjacent framed grid cells may touch. Grid requires columns such as "auto 1fr" or "1fr 1fr"; at most 12 tracks; no spans, pixel sizing, CSS or positioning.
            Text: h1,h2,h3,p,span,small,pre,br,hr. span accepts format="bold italic mono" (any subset). pre preserves spaces/newlines and uses monospace. Other text collapses formatting whitespace. No strong, em, code or output tags.
            label: for=input-id. input: name=form.path, type=text|number|checkbox|range (default text), placeholder, maxlength, readonly, disabled, min, max, step. Values come from form; no value or checked attributes. checkbox needs boolean. number/range need numeric values; text needs strings.
            textarea: name, rows (1..30), maxlength (1..8192), placeholder, readonly, disabled.
            select: name, disabled. Children are option or for-each producing options. option: value (string/number), disabled; text is its label. Selected value comes from form; no selected/multiple attributes.
            button: required id, disabled. Text is its label. No action, onclick or type attribute; clicking always submits the visual snapshot.
            ul/ol/li: lists; ol optionally has integer start. progress: numeric value, positive max (default 1); omit value for indeterminate progress. a: href with HTTP/HTTPS only, opened by the client upon click.
            if: test must be boolean; optional final <else> branch. Inactive branches are not evaluated.
            for-each: items must be an array, as is a kebab-case alias (not form/data/index), key is a unique scalar binding. The local index variable is available. Children are emitted directly, so a grid loop can emit several cells per iteration.

            Document and state: create supplies the reusable document plus both complete form/data sections. update normally supplies state only, containing complete data and/or complete form. Each supplied root replaces its whole previous object; all omitted members at any depth disappear. Omitted roots remain unchanged. Keep all nested values you want to retain. {"data":{}} clears data if schema and bindings allow it; {"form":{}} clears the whole form under the same rule. A state update must include at least one section. Document is changed only when layout, controls or schema change, not for ordinary content updates. create/update return a compact receipt; only get returns the full document. Reuse your known markup rather than reading it repeatedly.
            Inputs are local drafts until a button click submits the full {form,data} snapshot. Neutral dots mark the dirty tab and individual fields. Only submitted form is saved; input data is context, never a write-back. Prefer sending complete data only for routine updates. An explicit form section replaces all saved fields and local drafts, including values equal to their old saved values. Your client's own delayed button acknowledgement does not undo typing performed after the click.
            Handler input is one UTF-8 JSON line with visual-id,event-id,button-id,state. Loop on stdin and flush stdout after every NDJSON section update, such as {"data":{...all current data...}}. The same full-section replacement rules apply to handler output. Maintain complete display data in the script; do not echo the whole input state, which would overwrite the form. Send complete form only when intentionally resetting/replacing inputs. Updates are applied in order; rendering may coalesce snapshots.
            Conversation model requests automatically include saved Visual state in system-reminder blocks: full snapshots when changed, short unchanged references only while their full baseline is retained in the same request context. Client drafts are never included. Script updates do not wake the model; the next request sees the latest accepted state. get remains available for autonomous explicit refreshes, document inspection and diagnostics.
            Named secret references inside handler.command use the same secure environment substitution as grz_execute_command. Do not single-quote references in POSIX shell commands. References inside markup or state remain literal; they are never expanded into UI data.
            Highlighting: call action=highlight, visual_id, highlight=["field-id","button-id"]. Any currently rendered element with an explicit id can be targeted, including div containers and inline text. For loops use stable bound IDs. [] clears the set. This is an imperative presentation command, NOT a state update or HTML edit. It is sent once to connected clients; each client stores and dismisses it locally. Arrow badges clear the entire set without activating controls behind them; the target and its entire tab heading glow while the set is active. No highlights or dismissals are sent back in button state, get results or model reminders. Switching tabs preserves local highlights; reloading the client clears them. Unknown/not-rendered IDs fail. Do not put highlight in form/data or handler output.
            Limits: 64 KiB document, 32 KiB full state, 8 KiB output line, 2048 expanded nodes, 64 KiB expanded text, depth 32, 8 visuals per conversation. Runtime errors appear in a host-owned footer outside body; markup cannot hide it. Close/delete terminates the owned process; Worker restart stops it permanently until explicitly replaced. No scripts are automatically relaunched and no button events are automatically replayed.
        """.trimIndent()
    }
}
