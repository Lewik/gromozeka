package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.*
import kotlinx.serialization.Serializable

@Serializable
data class ApplicationUpdatePoll(
    val installationId: String,
    val component: UpdateComponent,
    val platform: UpdatePlatform,
    val label: String,
    val version: String,
    val report: ApplicationUpdateReport? = null,
    val unsupportedReason: String? = null,
)

@Serializable
data class ApplicationUpdatePollResponse(
    val installation: ApplicationInstallation,
    val available: ApplicationUpdateArtifact? = null,
)

@Serializable
data class ApplicationUpdateInventory(
    val installations: List<ApplicationInstallation>,
    val catalog: ApplicationUpdateCatalog? = null,
)

@Serializable
data class ApplicationUpdatePolicy(val automatic: Boolean)
