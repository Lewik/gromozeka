package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.tool.*
import com.gromozeka.infrastructure.ai.runtime.SdkHttpCallIdentity
import com.openai.core.jsonMapper
import com.openai.models.responses.ResponseInputItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat

/** Only opaque IDs and content fingerprints survive a call; canonical history stays in Harness. */
@Component
internal class OpenAiResponsesContinuations {
    private val lock = Any()
    private val cursors = LinkedHashMap<Scope, Cursor>(16, 0.75f, true)

    data class Scope(
        val user: String, val project: String, val conversation: String, val thread: String, val agent: String,
        val connection: String, val modelConfiguration: String, val model: String,
        val endpoint: String, val authFingerprint: String, val settingsFingerprint: String,
    )
    class Cursor(val responseId: String, val inputCount: Int, val fingerprint: String)
    enum class Reason { STATELESS, MISSING_SCOPE, ONE_USE_CONTEXT, NO_PARENT, PREFIX_CHANGED, INCREMENTAL, PREVIOUS_NOT_FOUND }
    data class Plan(
        val scope: Scope?, val observed: Cursor?, val fullInput: List<ResponseInputItem>,
        val input: List<ResponseInputItem>, val previousResponseId: String?, val store: Boolean, val reason: Reason,
    )

    fun scope(connection: AiConnection.OpenAiApi, modelConfiguration: String, model: String,
        request: AiRuntimeRequest, identity: SdkHttpCallIdentity): Scope? {
        if (request.options.usagePurpose != "CONVERSATION") return null
        fun field(key: String) = (request.options.toolContext[key] as? String)?.takeIf(String::isNotBlank)
        return Scope(
            user = field(TOOL_CONTEXT_USER_ID) ?: return null,
            project = field(TOOL_CONTEXT_PROJECT_ID) ?: return null,
            conversation = field(TOOL_CONTEXT_CONVERSATION_ID) ?: return null,
            thread = field(TOOL_CONTEXT_THREAD_ID) ?: return null,
            agent = field(TOOL_CONTEXT_AGENT_DEFINITION_ID) ?: return null,
            connection = connection.id.value, modelConfiguration = modelConfiguration, model = model,
            endpoint = identity.baseUrl,
            authFingerprint = identity.credentialFingerprint,
            settingsFingerprint = digest((Json.encodeToString(connection.httpSettings) + "\n" +
                Json.encodeToString(connection.executionTarget)).toByteArray()),
        )
    }

    fun plan(scope: Scope?, request: AiRuntimeRequest, input: List<ResponseInputItem>, enabled: Boolean): Plan {
        if (!enabled) return Plan(null, null, input, input, null, false, Reason.STATELESS)
        if (scope == null) return Plan(null, null, input, input, null, false, Reason.MISSING_SCOPE)
        val observed = synchronized(lock) { cursors[scope] }
        val hasOneUseContext = request.messages.any { message -> message.instructions.any {
            it is Conversation.Message.Instruction.RevealedSecretRuntimeContext
        } }
        if (hasOneUseContext) {
            forget(scope, observed)
            return Plan(scope, observed, input, input, null, false, Reason.ONE_USE_CONTEXT)
        }
        val reason = when {
            observed == null -> Reason.NO_PARENT
            observed.inputCount > input.size || fingerprint(input.subList(0, observed.inputCount)) != observed.fingerprint -> Reason.PREFIX_CHANGED
            else -> Reason.INCREMENTAL
        }
        return Plan(scope, observed, input,
            if (reason == Reason.INCREMENTAL) input.drop(observed!!.inputCount) else input,
            if (reason == Reason.INCREMENTAL) observed!!.responseId else null, true, reason)
    }

    fun record(plan: Plan, responseId: String, replay: List<ResponseInputItem>) {
        val scope = plan.scope ?: return
        if (!plan.store) return
        check(responseId.isNotBlank()) { "Stored Responses result has no response ID" }
        val expected = plan.fullInput + replay
        val next = Cursor(responseId, expected.size, fingerprint(expected))
        synchronized(lock) {
            // A stale concurrent completion must not replace a newer cursor.
            if (cursors[scope] !== plan.observed) return
            cursors[scope] = next
            while (cursors.size > MAX_CURSORS) cursors.remove(cursors.entries.first().key)
        }
    }

    fun forget(plan: Plan) { plan.scope?.let { forget(it, plan.observed) } }

    private fun forget(scope: Scope, observed: Cursor?) = synchronized(lock) {
        if (cursors[scope] === observed) cursors.remove(scope)
        Unit
    }

    private companion object {
        const val MAX_CURSORS = 128
        fun fingerprint(input: List<ResponseInputItem>): String = digest(jsonMapper().writeValueAsBytes(input))
        fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
