package com.gromozeka.domain.service

import com.gromozeka.domain.model.TranslationSaveResult
import com.gromozeka.domain.model.TranslationSnapshot
import com.gromozeka.domain.model.User
import com.gromozeka.shared.localization.TranslationPackage

interface UserTranslationService {
    suspend fun snapshot(userId: User.Id, clientInstanceId: String? = null): TranslationSnapshot

    suspend fun getPackage(userId: User.Id, selectionId: String): TranslationPackage

    suspend fun savePackage(
        userId: User.Id,
        translation: TranslationPackage,
        expectedRevision: Long,
        packageId: String? = null,
        clientInstanceId: String? = null,
    ): TranslationSaveResult

    suspend fun deletePackage(
        userId: User.Id,
        selectionId: String,
        expectedRevision: Long,
        clientInstanceId: String? = null,
    ): TranslationSnapshot

    suspend fun select(
        userId: User.Id,
        selectionId: String,
        expectedRevision: Long,
        clientInstanceId: String? = null,
    ): TranslationSnapshot

    suspend fun setSynchronizeClients(
        userId: User.Id,
        synchronizeClients: Boolean,
        expectedRevision: Long,
        clientInstanceId: String? = null,
    ): TranslationSnapshot
}

class TranslationRevisionConflictException(
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException("Translation state revision conflict: expected $expectedRevision, actual $actualRevision")
