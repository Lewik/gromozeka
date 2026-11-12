package com.gromozeka.domain.service

import com.gromozeka.domain.model.ApplicationUpdateCatalog

interface ApplicationUpdateCatalogSource {
    fun current(): ApplicationUpdateCatalog?
}
