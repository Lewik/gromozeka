package com.gromozeka.domain.service

import com.gromozeka.domain.model.RuntimeCatalogTemplates

interface RuntimeCatalogTemplateService {
    fun getTemplates(): RuntimeCatalogTemplates
}
