package com.gromozeka.client

import com.gromozeka.domain.model.RuntimeCatalogTemplates
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
import com.gromozeka.remote.protocol.GetRuntimeCatalogTemplatesRequest
import com.gromozeka.remote.protocol.RuntimeCatalogTemplatesResponse

internal class RemoteRuntimeCatalogTemplateService(
    private val client: GromozekaWsClient,
) : RuntimeCatalogTemplateService {
    private var templates: RuntimeCatalogTemplates? = null

    override fun getTemplates(): RuntimeCatalogTemplates =
        checkNotNull(templates) { "Runtime catalog templates are not loaded" }

    suspend fun reload(): RuntimeCatalogTemplates =
        client.requestTyped<GetRuntimeCatalogTemplatesRequest, RuntimeCatalogTemplatesResponse>(
            GetRuntimeCatalogTemplatesRequest
        ).templates.also { templates = it }
}
