package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryTask
import java.util.UUID

class UuidMemoryIdFactory(
    private val prefix: String = "memory",
) : MemoryIdFactory {
    override fun newEntityId(): MemoryEntity.Id =
        MemoryEntity.Id(newId("entity"))

    override fun newClaimId(): MemoryClaim.Id =
        MemoryClaim.Id(newId("claim"))

    override fun newNoteId(): MemoryNote.Id =
        MemoryNote.Id(newId("note"))

    override fun newTaskId(): MemoryTask.Id =
        MemoryTask.Id(newId("task"))

    override fun newEpisodeId(): MemoryEpisode.Id =
        MemoryEpisode.Id(newId("episode"))

    override fun newRunId(): MemoryRun.Id =
        MemoryRun.Id(newId("run"))

    private fun newId(kind: String): String =
        "$prefix:$kind:${UUID.randomUUID()}"
}
