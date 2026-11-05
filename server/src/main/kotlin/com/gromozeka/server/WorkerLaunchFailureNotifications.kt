package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.UserNotification
import com.gromozeka.domain.model.UserNotificationDraft
import com.gromozeka.domain.service.StoredWorkerRequest
import com.gromozeka.domain.service.UserNotificationPublisher
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME
import com.gromozeka.remote.protocol.*
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.security.MessageDigest

/** One durable producer; neither Worker cleanup nor gateway exceptions publish their own inbox entry. */
@Service
class WorkerLaunchFailureNotifications(private val notifications: UserNotificationPublisher) : WorkerRequestCompletionObserver {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun completed(record: StoredWorkerRequest, response: WorkerGatewayMessage.Response) {
        val actor = record.actorUserId ?: return
        val request = WorkerGatewayCodec.decode(record.request) as WorkerGatewayMessage.Request
        when (request.operation) {
            WorkerGatewayOperation.TOOL_EXECUTION -> {
                val execution = json.decodeFromString<WorkerToolExecutionRequest>(request.payload.decodeToString())
                val calls = execution.toolCalls.filter { it.call.name == GRZ_EXECUTE_COMMAND_TOOL_NAME || it.call.name == "grz_monitor_command" }
                if (calls.isEmpty()) return
                val result = if (response.status == WorkerGatewayMessage.Response.Status.SUCCEEDED) {
                    json.decodeFromString<WorkerToolExecutionResponse>(requireNotNull(response.payload).decodeToString()).results
                } else null
                require(result == null || result.map { it.toolUseId }.distinct().size == result.size) { "Duplicate Worker tool result" }
                for (call in calls) {
                    if (result != null) {
                        val item = requireNotNull(result.singleOrNull { it.toolUseId == call.id }) { "Missing Worker tool result" }
                        require(item.toolName == call.call.name) { "Worker tool result does not match its request" }
                        if (!item.isError) continue // A shell exit code is not an RPC/launch failure.
                    }
                    val kind = if (call.call.name == GRZ_EXECUTE_COMMAND_TOOL_NAME) "Command" else "Monitor"
                    notifications.publish(actor, UserNotificationDraft(
                        title = "$kind launch needs attention",
                        message = message(record, response),
                        level = UserNotification.Level.WARNING,
                        conversationId = execution.toolContext[TOOL_CONTEXT_CONVERSATION_ID]?.let(Conversation::Id),
                        sourceKey = "worker-launch:${record.id}:${call.id.value.digest()}",
                    ))
                }
            }
            WorkerGatewayOperation.VISUAL_COMMAND -> {
                if (response.status == WorkerGatewayMessage.Response.Status.SUCCEEDED) return
                val visual = json.decodeFromString<VisualWorkerCommand>(request.payload.decodeToString())
                if (visual.operation != VisualWorkerCommand.Operation.START) return
                notifications.publish(actor, UserNotificationDraft(
                    title = "Visual handler launch needs attention",
                    message = message(record, response),
                    level = UserNotification.Level.WARNING,
                    conversationId = visual.conversationId,
                    sourceKey = "worker-launch:${record.id}:visual",
                ))
            }
            else -> Unit
        }
    }

    private fun message(record: StoredWorkerRequest, response: WorkerGatewayMessage.Response): String {
        val explanation = when {
            record.dispatchedAt == null -> "The launch request ended before dispatch to the Worker; no command was launched by this request."
            response.errorCode == "OUTCOME_UNKNOWN" -> "The Worker response deadline passed. The launch outcome is unknown."
            else -> "The Worker reported an error while handling the launch request."
        }
        val caution = if (record.dispatchedAt == null) "" else
            " The process may have started or produced partial effects. Check the existing task or monitor before retrying; it is not restarted automatically."
        return "$explanation$caution\nWorker: ${record.workerId.value}\nRequest: ${record.id}"
    }

    private fun String.digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
