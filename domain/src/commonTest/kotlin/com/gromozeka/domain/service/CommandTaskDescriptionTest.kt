package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class CommandTaskDescriptionTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val now = Instant.fromEpochMilliseconds(0)
    private fun task(description: String? = null) = CommandTask(
        id = CommandTask.Id("command"), conversationId = Conversation.Id("conversation"),
        workerId = ConversationRuntimeWorkerId("worker"), workspaceMountId = WorkspaceMount.Id("mount"),
        command = "echo original", description = description, workingDirectory = "/workspace",
        status = CommandTask.Status.WORKING, processId = null, processStartedAt = null,
        outputFile = "/output", outputBytes = 0, createdAt = now, updatedAt = now,
    )

    @Test fun labelSurvivesSerializationAndTerminalTransition() {
        val description = "д".repeat(1000)
        val original = task(description)
        assertEquals(original, json.decodeFromString<CommandTask>(json.encodeToString(CommandTask.serializer(), original)))
        val completed = original.copy(status = CommandTask.Status.COMPLETED, exitCode = 0, completedAt = now)
        assertEquals(description, completed.displayLabel)
        assertEquals("echo original", completed.command)
        assertFailsWith<IllegalArgumentException> { original.copy(description = "changed").requireSameIdentity(original) }
    }

    @Test fun oldOrBlankDescriptionsUseTheOriginalCommandWithoutInventingALabel() {
        val original = task()
        val payload = json.encodeToJsonElement(CommandTask.serializer(), original).jsonObject
        assertEquals(original, json.decodeFromJsonElement<CommandTask>(JsonObject(payload - "description")))
        assertEquals("echo original", original.displayLabel)
        assertEquals("echo original", task(" \n ").displayLabel)
        assertEquals("x".repeat(51), task("x".repeat(51)).displayLabel)
        assertEquals("😀".repeat(1000), task("😀".repeat(1000)).description)
        assertFailsWith<IllegalArgumentException> { task("x".repeat(1001)) }
    }
}
