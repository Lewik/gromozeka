package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.service.AgentSkillDirectoryImportRequest
import com.gromozeka.domain.service.AgentSkillPackageRequest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@OptIn(ExperimentalSerializationApi::class)
object WorkerAgentSkillPackageGatewayCodec {
    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: AgentSkillPackageRequest): ByteArray =
        cbor.encodeToByteArray(request)

    fun decodeRequest(payload: ByteArray): AgentSkillPackageRequest =
        cbor.decodeFromByteArray(payload)

    fun encodeResult(skillPackage: AgentSkillPackage): ByteArray =
        cbor.encodeToByteArray(skillPackage)

    fun decodeResult(payload: ByteArray): AgentSkillPackage =
        cbor.decodeFromByteArray(payload)
}

@OptIn(ExperimentalSerializationApi::class)
object WorkerAgentSkillImportGatewayCodec {
    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: AgentSkillDirectoryImportRequest): ByteArray =
        cbor.encodeToByteArray(request)

    fun decodeRequest(payload: ByteArray): AgentSkillDirectoryImportRequest =
        cbor.decodeFromByteArray(payload)

    fun encodeResult(skill: AgentSkill): ByteArray =
        cbor.encodeToByteArray(skill)

    fun decodeResult(payload: ByteArray): AgentSkill =
        cbor.decodeFromByteArray(payload)
}
