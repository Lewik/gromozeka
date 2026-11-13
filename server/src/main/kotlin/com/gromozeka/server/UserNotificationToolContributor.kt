package com.gromozeka.server

import com.gromozeka.application.service.UserNotificationApplicationService
import com.gromozeka.domain.model.UserNotification
import com.gromozeka.domain.model.UserNotificationCursor
import com.gromozeka.domain.model.UserNotificationPage
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.springframework.stereotype.Service
import kotlin.time.Instant

@Service
internal class UserNotificationToolContributor(
    factory: ServerJsonToolFactory,
    notifications: UserNotificationApplicationService,
) : AiToolCallbackContributor {
    private val inputJson = Json { ignoreUnknownKeys = false }

    override val callbacks: List<AiToolCallback> = listOf(
        factory.create(
            CONTROL_TOOL_SOURCE,
            name = "grz_user_notifications_list",
            description = "Read the authenticated user's notifications, newest first. Reading never marks notifications as read. Use nextCursor as before to continue; omit before for the first page. No recipient selector or access to another user's inbox.",
            inputSchema = inputJson.parseToJsonElement("""{
                "type":"object","properties":{
                    "limit":{"type":"integer","minimum":1,"maximum":100,"default":50},
                    "unread_only":{"type":"boolean","default":false},
                    "before":{"type":"object","properties":{
                        "createdAt":{"type":"string","format":"date-time"},
                        "id":{"type":"string","minLength":1,"maxLength":128}
                    },"required":["createdAt","id"],"additionalProperties":false}
                },"additionalProperties":false
            }""").jsonObject,
            readOnly = true,
            idempotent = true,
        ) { input ->
            require(input.keys.all { it in setOf("before", "limit", "unread_only") }) { "Unsupported notification selector" }
            val before = if ("before" in input) input.requiredObject("before").let { cursor ->
                require(cursor.keys == setOf("createdAt", "id")) { "Invalid notification cursor" }
                UserNotificationCursor(Instant.parse(cursor.requiredString("createdAt")), UserNotification.Id(cursor.requiredString("id")))
            } else null
            serverToolJson.encodeToJsonElement(UserNotificationPage.serializer(),
                notifications.list(user.id, before, input.optionalInt("limit", 50, 1..100),
                    input.optionalBoolean("unread_only", false))).jsonObject
        },
        factory.create(
            CONTROL_TOOL_SOURCE,
            name = "grz_user_notification_mark_read",
            description = "Mark one of the authenticated user's notifications as read. Repeating the call preserves the original read time. Does not mark other notifications or delete anything.",
            inputSchema = inputJson.parseToJsonElement("""{
                "type":"object","properties":{
                    "notification_id":{"type":"string","minLength":1,"maxLength":128}
                },"required":["notification_id"],"additionalProperties":false
            }""").jsonObject,
            readOnly = false,
            idempotent = true,
        ) { input ->
            require(input.keys == setOf("notification_id")) { "Only notification_id is accepted" }
            serverToolJson.encodeToJsonElement(UserNotification.serializer(),
                notifications.setRead(user.id, UserNotification.Id(input.requiredString("notification_id")), true)).jsonObject
        },
    )
}
