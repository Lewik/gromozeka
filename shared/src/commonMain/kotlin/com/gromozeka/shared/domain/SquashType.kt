package com.gromozeka.shared.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SquashType {
    CONCATENATE,
    DISTILL,
    SUMMARIZE
}
