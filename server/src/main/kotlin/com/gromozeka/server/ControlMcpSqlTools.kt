package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.RuntimeSqlAccessDeniedException
import com.gromozeka.domain.service.RuntimeSqlExecutionException
import com.gromozeka.domain.service.RuntimeSqlRequest
import com.gromozeka.domain.service.RuntimeSqlResult
import com.gromozeka.domain.service.RuntimeSqlService
import kotlinx.serialization.json.jsonObject
import org.springframework.stereotype.Service

@Service
internal class ControlMcpSqlTools(
    private val sql: RuntimeSqlService,
    private val conversations: ConversationRepository,
) : ControlMcpToolProvider {
    override val tools = listOf(
        controlMcpTool(
            name = "grz_sql",
            description = "Execute PostgreSQL SQL against this Runtime database with the application's full database privileges. " +
                "Supports reads, writes, DDL and multiple statements; this is NOT a read-only tool or sandbox. " +
                "Only the sole login-enabled Runtime owner may use it. External-channel conversations, including Telegram, are forbidden. " +
                "Uses a fresh autocommit session per call; explicitly opened transactions left uncommitted are rolled back on exit. " +
                "No automatic retries: an error or cancellation may occur after earlier statements committed. " +
                "Results contain ordered columns, text-valued rows (SQL NULL is null), update counts and explicit truncation flags. " +
                "Output is bounded (100 result sets, 16384 characters per cell, approximately 1 MB total); this does not limit write effects. " +
                "For device telemetry, inspect context_state_events and context_state_projections, retaining observed_at versus received_at. " +
                "Use information_schema or pg_catalog to discover the actual schema. Direct SQL bypasses application validation, caches, " +
                "state-sync notifications and ordinary business audit hooks; prefer dedicated tools for routine configuration changes.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "sql" to ControlMcpSchemas.string("SQL statement or script to execute. No table or statement allowlist."),
                    "max_rows" to ControlMcpSchemas.integer("Maximum returned rows per result set; default 1000. Only output is limited, not affected rows.", 1, 10_000),
                    "timeout_seconds" to ControlMcpSchemas.integer("Optional PostgreSQL statement timeout in seconds; 0 (default) means no tool-imposed timeout.", 0, 86_400),
                ),
                required = listOf("sql"),
            ),
            readOnly = false,
            destructive = true,
            idempotent = false,
            accessPolicy = ControlMcpAccessPolicy.SERVER_OWNER,
        ) { input ->
            // Origin comes only from trusted runtime context, never from tool arguments.
            if (callingAgentId != null && conversationId == null) {
                throw ControlMcpToolException("forbidden", "Conversation SQL calls require a trusted conversation identity")
            }
            conversationId?.let { id ->
                val conversation = conversations.findById(id)
                    ?: throw ControlMcpToolException("forbidden", "SQL conversation is unavailable")
                if (conversation.externalChannel != null || Conversation.Participant.User(user.id) !in conversation.participants ||
                    callingAgentId?.let { Conversation.Participant.Agent(it) !in conversation.participants } == true) {
                    throw ControlMcpToolException("forbidden", "SQL is only available in a private conversation of the Runtime owner")
                }
            }
            try {
                controlMcpJson.encodeToJsonElement(RuntimeSqlResult.serializer(), sql.execute(
                    user.id,
                    RuntimeSqlRequest(input.requiredString("sql"), input.optionalInt("max_rows", 1_000, 1..10_000),
                        input.optionalInt("timeout_seconds", 0, 0..86_400)),
                    cancellationSignal,
                )).jsonObject
            } catch (error: RuntimeSqlAccessDeniedException) {
                throw ControlMcpToolException("forbidden", error.message.orEmpty())
            } catch (error: RuntimeSqlExecutionException) {
                throw ControlMcpToolException("sql_error", "SQLSTATE ${error.sqlState ?: "unknown"}: ${error.message}")
            }
        },
    )
}
