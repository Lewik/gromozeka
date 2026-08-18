package com.gromozeka.application.service

import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.StoredNamedSecret
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.NamedSecretRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NamedSecretApplicationServiceTest {
    @Test
    fun `save normalizes name and rotation preserves identity`() = runBlocking {
        val repository = InMemoryNamedSecretRepository()
        val audit = FakeSecurityAuditRecorder()
        val service = NamedSecretApplicationService(repository, audit)
        val userId = User.Id("user")

        val created = service.save(userId, " GitHub-PAT ", "first", "token-1")
        val rotated = service.save(userId, "github-pat", "second", "token-2")

        assertEquals("github-pat", created.name)
        assertEquals(created.id, rotated.id)
        assertEquals("token-2", service.resolve(userId, setOf("github-pat")).getValue("github-pat"))
        assertEquals(2, audit.records.size)
    }

    @Test
    fun `tool resolution returns values separately from original calls`() = runBlocking {
        val repository = InMemoryNamedSecretRepository()
        val service = NamedSecretApplicationService(repository, FakeSecurityAuditRecorder())
        val userId = User.Id("user")
        service.save(userId, "github-pat", "", "actual-token")
        val call = Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = "tool",
                input = JsonObject(mapOf("token" to JsonPrimitive("secret://github-pat"))),
            ),
        )

        val resolved = ToolSecretResolutionService(service).resolve(userId, listOf(call))

        assertEquals(mapOf("github-pat" to "actual-token"), resolved.getValue("call"))
        assertEquals("secret://github-pat", call.call.input.jsonObject.getValue("token").jsonPrimitive.content)
    }
}

private class InMemoryNamedSecretRepository : NamedSecretRepository {
    private val values = mutableMapOf<Pair<User.Id, String>, StoredNamedSecret>()

    override suspend fun list(userId: User.Id): List<NamedSecret> = values
        .filterKeys { it.first == userId }
        .values
        .map(StoredNamedSecret::metadata)

    override suspend fun find(userId: User.Id, name: String): StoredNamedSecret? = values[userId to name]

    override suspend fun save(secret: NamedSecret, value: String): NamedSecret {
        values[secret.userId to secret.name] = StoredNamedSecret(secret, value)
        return secret
    }

    override suspend fun delete(userId: User.Id, secretId: NamedSecret.Id): Boolean {
        val key = values.entries.singleOrNull { (key, value) ->
            key.first == userId && value.metadata.id == secretId
        }?.key ?: return false
        values.remove(key)
        return true
    }
}
