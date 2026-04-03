package com.gromozeka.presentation.testsupport.config

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryLink
import com.gromozeka.domain.model.memory.MemoryObject
import com.gromozeka.domain.repository.KnowledgeGraphRepository
import com.gromozeka.domain.repository.MemoryManagementService
import com.gromozeka.domain.service.AudioController
import com.gromozeka.domain.service.VectorMemoryService
import com.gromozeka.infrastructure.ai.memory.KnowledgeGraphServiceFacade
import com.gromozeka.infrastructure.ai.platform.GlobalHotkeyController
import com.gromozeka.infrastructure.ai.platform.NoOpGlobalHotkeyController
import com.gromozeka.infrastructure.ai.platform.ScreenCaptureController
import com.gromozeka.infrastructure.ai.platform.SystemAudioController
import io.mockk.coEvery
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile

/**
 * Test-only Spring overrides that disable hardware and heavy integrations.
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("e2e")
class E2eSupportConfig {

    @Bean
    @Primary
    fun screenCaptureController(): ScreenCaptureController = object : ScreenCaptureController {
        override suspend fun captureWindow(): String? = null

        override suspend fun captureFullScreen(): String? = null

        override suspend fun captureArea(): String? = null
    }

    @Bean
    @Primary
    fun systemAudioController(): SystemAudioController = object : SystemAudioController {
        override suspend fun mute(): Boolean = true

        override suspend fun unmute(): Boolean = true

        override suspend fun isSystemMuted(): Boolean = false

        override suspend fun setVolume(level: Float): Boolean = true

        override suspend fun getVolume(): Float = 1.0f
    }

    @Bean
    @Primary
    fun audioController(): AudioController = object : AudioController {
        override suspend fun playAudioFile(filePath: String) = Unit

        override suspend fun stopPlayback() = Unit
    }

    @Bean
    @Primary
    fun vectorMemoryService(): VectorMemoryService = object : VectorMemoryService {
        override suspend fun rememberThread(threadId: String) = Unit

        override suspend fun recall(
            query: String,
            threadId: String?,
            limit: Int,
        ): List<VectorMemoryService.Memory> = emptyList()

        override suspend fun forgetMessage(messageId: String) = Unit
    }

    @Bean
    @Primary
    fun knowledgeGraphRepository(): KnowledgeGraphRepository = object : KnowledgeGraphRepository {
        override suspend fun saveEntities(entities: List<MemoryObject>) = Unit

        override suspend fun saveRelationships(relationships: List<MemoryLink>) = Unit

        override suspend fun saveToGraph(
            entities: List<MemoryObject>,
            relationships: List<MemoryLink>,
        ) = Unit

        override suspend fun findEntityByName(name: String, groupId: String): MemoryObject? = null

        override suspend fun findEntityByUuid(uuid: String): MemoryObject? = null

        override suspend fun executeQuery(
            cypher: String,
            params: Map<String, Any>,
        ): List<Map<String, Any>> = emptyList()

        override suspend fun syncProject(project: Project) = Unit

        override suspend fun deleteCodeSpecsByProject(projectId: String): Int = 0

        override suspend fun findProjectEntity(projectId: String): MemoryObject? = null
    }

    @Bean
    @Primary
    fun memoryManagementService(): MemoryManagementService = object : MemoryManagementService {
        override suspend fun addFactDirectly(
            from: String,
            relation: String,
            to: String,
            summary: String?,
            validAt: String?,
            invalidAt: String?,
        ): String = "E2E memory management is disabled"

        override suspend fun getEntityDetails(name: String): String = "E2E memory management is disabled"

        override suspend fun invalidateFact(
            from: String,
            relation: String,
            to: String,
        ): String = "E2E memory management is disabled"

        override suspend fun updateEntity(
            name: String,
            newSummary: String?,
            newType: String?,
        ): String = "E2E memory management is disabled"

        override suspend fun hardDeleteEntity(
            name: String,
            cascade: Boolean,
        ): String = "E2E memory management is disabled"
    }

    @Bean
    @Primary
    fun knowledgeGraphServiceFacade(): KnowledgeGraphServiceFacade {
        return mockk(relaxed = true) {
            coEvery { extractAndSaveToGraph(any(), any(), any(), any()) } returns "E2E knowledge graph is disabled"
        }
    }

    @Bean
    @Primary
    fun globalHotkeyController(): GlobalHotkeyController = NoOpGlobalHotkeyController()
}
