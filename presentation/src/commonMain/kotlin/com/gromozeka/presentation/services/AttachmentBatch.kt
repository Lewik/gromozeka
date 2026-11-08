package com.gromozeka.presentation.services

import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedPlural
import com.gromozeka.presentation.services.translation.localizedText

internal suspend fun <T> readAttachmentBatch(
    items: List<T>,
    read: suspend (T) -> ArtifactUpload,
): List<ArtifactUpload> {
    if (items.size > ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE) {
        throw LocalizedTextException(localizedPlural("client.attachment.tooMany", ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE.toLong()))
    }
    val uploads = mutableListOf<ArtifactUpload>()
    var total = 0L
    for (item in items) {
        val upload = read(item)
        if (upload.content.size > ArtifactLimits.MAX_FILE_BYTES) {
            throw LocalizedTextException(localizedText("client.attachment.fileTooLarge", "file" to upload.fileName,
                "limit" to ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024)))
        }
        total += upload.content.size
        if (total > ArtifactLimits.MAX_TOTAL_BYTES_PER_MESSAGE) {
            throw LocalizedTextException(localizedText("client.attachment.totalTooLarge",
                "limit" to ArtifactLimits.MAX_TOTAL_BYTES_PER_MESSAGE / (1024 * 1024)))
        }
        uploads += upload
    }
    return uploads
}
