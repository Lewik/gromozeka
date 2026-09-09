package com.gromozeka.server.telegram

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_TELEGRAM_LIVE_PROBE", matches = "true")
class TelegramLiveProbeTest {
    @Test
    fun `inspect explicitly configured bot without sending messages or acknowledging updates`(): Unit = runBlocking {
        val path = Path.of(requireNotNull(System.getenv("GROMOZEKA_TELEGRAM_TEST_TOKEN_FILE")))
        val token = Files.readString(path).trim()
        val api = TelegramHttpApi(token)
        val expectedUsername = requireNotNull(System.getenv("GROMOZEKA_TELEGRAM_TEST_BOT_USERNAME"))
        val bot = api.call("getMe").jsonObject
        assertEquals(expectedUsername.lowercase(), bot.string("username")?.lowercase())
        println(buildJsonObject {
            put("probe", "getMe")
            put("username", bot.string("username"))
            put("id", bot.long("id"))
            put("canJoinGroups", bot.boolean("can_join_groups"))
            put("canReadAllGroupMessages", bot.boolean("can_read_all_group_messages"))
        })
        val webhook = api.call("getWebhookInfo").jsonObject
        assertTrue(webhook.string("url").isNullOrEmpty(), "Bot already has a webhook; do not interfere with its receiver")
        println(buildJsonObject {
            put("probe", "getWebhookInfo")
            put("webhookConfigured", false)
            put("pendingUpdateCount", webhook.long("pending_update_count"))
        })
        val updates = api.call("getUpdates", buildJsonObject { put("timeout", 0); put("limit", 100) }).jsonArray
        println(buildJsonObject {
            put("probe", "getUpdates")
            put("count", updates.size)
            put("metadata", buildJsonArray {
                updates.forEach { element ->
                    val update = element.jsonObject
                    val event = (update["message"] ?: update["edited_message"] ?: update["my_chat_member"]) as? JsonObject
                    val chat = event?.get("chat") as? JsonObject
                    val from = event?.get("from") as? JsonObject
                    add(buildJsonObject {
                        put("updateId", update.long("update_id"))
                        put("eventTypes", JsonArray(update.keys.filter { it != "update_id" }.map(::JsonPrimitive)))
                        put("chatId", chat?.long("id"))
                        put("chatType", chat?.string("type"))
                        put("messageId", event?.long("message_id"))
                        put("topicId", event?.long("message_thread_id"))
                        put("fromId", from?.long("id"))
                        put("fromUsername", from?.string("username"))
                        put("authorIsBot", from?.boolean("is_bot"))
                        put("hasText", event?.containsKey("text") == true)
                    })
                }
            })
        })
    }
}
