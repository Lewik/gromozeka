package com.gromozeka.domain.tool

enum class ProviderNativeTool(val source: String, val toolName: String) {
    OPENAI_API_WEB_SEARCH("provider:openai_api", "web_search"),
    OPENAI_SUBSCRIPTION_WEB_SEARCH("provider:openai_subscription", "web_search");

    val descriptor: AiToolDescriptor
        get() = AiToolDescriptor(
            AiToolDefinition(toolName, "Provider-hosted public web search and page reading.", "{\"type\":\"object\",\"properties\":{}}", source),
            AiToolMetadata(executionScope = AiToolExecutionScope.SERVER, loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE),
        )

    fun isAllowed(policy: ToolAccessPolicy): Boolean =
        policy.allows(QualifiedToolName(source, toolName), descriptor.contractFingerprint())

    fun catalogEntry() = AgentToolCatalogEntry(
        QualifiedToolName(source, toolName), ToolContractFingerprint(descriptor.contractFingerprint()),
        "$source/$toolName", null, available = true, providerNative = true,
    )
}
