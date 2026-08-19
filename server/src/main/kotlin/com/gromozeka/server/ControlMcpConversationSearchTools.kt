package com.gromozeka.server

import com.gromozeka.application.service.ConversationSearchApplicationService
import com.gromozeka.domain.model.CONVERSATION_SEARCH_DEFAULT_LIMIT
import com.gromozeka.domain.model.CONVERSATION_SEARCH_MAX_LIMIT
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class ControlMcpConversationSearchTools(
    private val conversationSearchService: ConversationSearchApplicationService,
) : ControlMcpToolProvider {
    override val tools: List<ControlMcpTool> = listOf(
        controlMcpTool(
            name = "grz_message_search",
            description = """
                Search visible user and assistant message text across accessible Gromozeka conversations.
                Results contain bounded excerpts and an opaque cursor, never complete messages or internal tool payloads.
                Pass nextCursor as cursor to continue the same search without repeating earlier hits.
            """.trimIndent(),
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "query" to ControlMcpSchemas.string("Text to find in conversation messages."),
                    "projectId" to ControlMcpSchemas.string("Optional project id filter."),
                    "conversationId" to ControlMcpSchemas.string("Optional conversation id filter."),
                    "roles" to ControlMcpSchemas.stringArray(
                        "Optional roles to search: USER and/or ASSISTANT. Defaults to both."
                    ),
                    "threadScope" to ControlMcpSchemas.string(
                        description = "Search CURRENT conversation threads or ALL historical branches.",
                        enum = ConversationSearchRequest.ThreadScope.entries.map { it.name },
                    ),
                    "limit" to ControlMcpSchemas.integer(
                        description = "Maximum hits in this page.",
                        minimum = 1,
                        maximum = CONVERSATION_SEARCH_MAX_LIMIT,
                    ),
                    "cursor" to ControlMcpSchemas.string("Opaque nextCursor from the previous page."),
                ),
                required = listOf("query"),
            ),
            readOnly = true,
        ) { input ->
            val roleNames = if ("roles" in input) input.requiredStringList("roles") else emptyList()
            val roles = if (roleNames.isEmpty()) {
                setOf(Conversation.Message.Role.USER, Conversation.Message.Role.ASSISTANT)
            } else {
                roleNames.mapTo(linkedSetOf()) { role ->
                    runCatching { Conversation.Message.Role.valueOf(role.uppercase()) }
                        .getOrElse {
                            throw ControlMcpToolException(
                                "invalid_argument",
                                "'roles' accepts only USER and ASSISTANT",
                            )
                        }
                        .also {
                            if (it == Conversation.Message.Role.SYSTEM) {
                                throw ControlMcpToolException(
                                    "invalid_argument",
                                    "'roles' accepts only USER and ASSISTANT",
                                )
                            }
                        }
                }
            }
            val threadScope = input.optionalString("threadScope")
                ?.let { value ->
                    runCatching { ConversationSearchRequest.ThreadScope.valueOf(value.uppercase()) }
                        .getOrElse {
                            throw ControlMcpToolException(
                                "invalid_argument",
                                "'threadScope' accepts CURRENT or ALL",
                            )
                        }
                }
                ?: ConversationSearchRequest.ThreadScope.CURRENT
            val page = conversationSearchService.search(
                actorUserId = user.id,
                request = ConversationSearchRequest(
                    query = input.requiredString("query"),
                    projectId = input.optionalString("projectId")?.let { Project.Id(it) },
                    conversationId = input.optionalString("conversationId")?.let { Conversation.Id(it) },
                    roles = roles,
                    threadScope = threadScope,
                    includeMetadataMatches = false,
                    limit = input.optionalInt(
                        name = "limit",
                        default = CONVERSATION_SEARCH_DEFAULT_LIMIT,
                        range = 1..CONVERSATION_SEARCH_MAX_LIMIT,
                    ),
                    cursor = input.optionalString("cursor"),
                ),
            )

            buildJsonObject {
                put(
                    "hits",
                    JsonArray(
                        page.hits.map { hit ->
                            buildJsonObject {
                                put("projectId", hit.project.id.value)
                                put("projectName", hit.project.name)
                                put("conversationId", hit.conversation.id.value)
                                put("conversationName", hit.conversation.displayName)
                                put("threadId", hit.threadId.value)
                                hit.messageId?.let { put("messageId", it.value) }
                                hit.role?.let { put("role", it.name) }
                                put("createdAt", hit.matchedAt.toString())
                                put("excerpt", hit.excerpt)
                            }
                        }
                    )
                )
                page.nextCursor?.let { put("nextCursor", it) }
                put("hasMore", page.nextCursor != null)
            }
        }
    )
}
