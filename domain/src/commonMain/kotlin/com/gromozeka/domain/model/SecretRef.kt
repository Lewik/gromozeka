package com.gromozeka.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("secretType")
sealed interface SecretRef {
    @Serializable
    @SerialName("inline")
    data class Inline(val value: String) : SecretRef {
        init {
            require(value.isNotBlank()) { "Inline secret must not be blank" }
        }
    }

    @Serializable
    @SerialName("environment_variable")
    data class EnvironmentVariable(val name: String) : SecretRef {
        init {
            require(name.isNotBlank()) { "Secret environment variable name must not be blank" }
        }
    }
}
