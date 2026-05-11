package com.gromozeka.presentation.services

class OllamaModelService {
    data class ModelListResult(
        val models: List<String> = emptyList(),
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    fun listModels(): ModelListResult =
        ModelListResult(error = "Ollama model listing is not available in the remote UI client yet")
}
