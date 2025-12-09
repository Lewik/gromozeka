/*
 * Copyright 2023-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * GROMOZEKA MODIFICATIONS:
 * - Fixed prompt caching strategy for CONVERSATION_HISTORY
 * - Cache control is now applied only to system message and last content block
 * - This prevents wasteful breakpoint usage on intermediate assistant/tool messages
 */

package org.springframework.ai.anthropic

import io.micrometer.observation.ObservationRegistry
import klog.KLoggers
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.anthropic.api.AnthropicApi.*
import org.springframework.ai.anthropic.api.AnthropicCacheOptions
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy
import org.springframework.ai.anthropic.api.utils.CacheEligibilityResolver
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.util.json.JsonParser
import org.springframework.retry.support.RetryTemplate

/**
 * Fixed version of AnthropicChatModel with corrected prompt caching strategy.
 *
 * GROMOZEKA: This class extends EnhancedAnthropicChatModel and overrides createRequest to fix the
 * prompt caching strategy. Instead of applying cache control to every assistant/tool message
 * (which wastes breakpoints), we only apply it to:
 * 1. System message (for cross-conversation reuse)
 * 2. Last content block in the message history (regardless of message type)
 *
 * This correctly implements CONVERSATION_HISTORY caching with minimal breakpoint usage.
 *
 * @author Spring AI Authors (original)
 * @author Gromozeka Team (cache fixes)
 * @since 1.0.0
 */
class PromptCachingFixedAnthropicChatModel(
    anthropicApi: AnthropicApi,
    defaultOptions: AnthropicChatOptions,
    toolCallingManager: ToolCallingManager,
    retryTemplate: RetryTemplate,
    observationRegistry: ObservationRegistry,
) : EnhancedAnthropicChatModel(
    anthropicApi,
    defaultOptions,
    toolCallingManager,
    retryTemplate,
    observationRegistry
) {
    private val log = KLoggers.logger(this)

    /**
     * GROMOZEKA FIX: Override createRequest to apply fixed cache control logic.
     *
     * The key change is in how cache control is applied:
     * - System message gets cache_control (for cross-conversation reuse)
     * - Only the LAST content block (across all messages) gets cache_control
     * - Intermediate assistant/tool messages do NOT get cache_control
     * - This saves breakpoints and correctly implements CONVERSATION_HISTORY caching
     */
    override fun createRequest(prompt: Prompt, stream: Boolean): ChatCompletionRequest {
        log.debug { "=== PromptCachingFixedAnthropicChatModel.createRequest() START ===" }

        val promptWithoutCache = Prompt(
            prompt.instructions,
            AnthropicChatOptions
                .fromOptions(prompt.options as? AnthropicChatOptions ?: AnthropicChatOptions.builder().build())
                .also { it.cacheOptions = AnthropicCacheOptions.DISABLED }
        )
        val baseRequest = super.createRequest(promptWithoutCache, stream)
        log.debug { "baseRequest created with ${baseRequest.messages()?.size} messages (cache disabled for super call)" }

        val cacheOptions = (prompt.options as? AnthropicChatOptions)?.cacheOptions
            ?: AnthropicCacheOptions.DISABLED
        val cacheEligibilityResolver = CacheEligibilityResolver.from(cacheOptions)

        log.debug { "CacheEligibilityResolver created, strategy=${cacheOptions.strategy}, cachingEnabled=${cacheEligibilityResolver.isCachingEnabled}" }

        return if (
            cacheEligibilityResolver.isCachingEnabled
            && !baseRequest.messages().isNullOrEmpty()
            && cacheOptions.strategy == AnthropicCacheStrategy.CONVERSATION_HISTORY
        ) {
            log.debug { "Applying fixed cache control to messages, system, and tools" }

            val systemPrompts = baseRequest.system() as? List<ContentBlock>

            val fixedSystem = systemPrompts?.let {
                log.debug { "Processing ${systemPrompts.size} system blocks" }
                applySystemCacheControl(systemPrompts, cacheEligibilityResolver)
            }

            // Apply cache control to tools
            val fixedTools = baseRequest.tools()?.let { tools ->
                log.debug { "Processing ${tools.size} tools" }
                applyToolsCacheControl(tools, cacheEligibilityResolver)
            }

            // Apply cache control to message blocks
            val fixedMessages = applyCacheControl(
                baseRequest.messages(),
                cacheEligibilityResolver
            )
            log.debug { "Fixed cache control applied, system/tools/messages processed" }
            ChatCompletionRequest
                .from(baseRequest)
                .system(fixedSystem)
                .tools(fixedTools)
                .messages(fixedMessages)
                .build()
                .also { log.debug { "=== PromptCachingFixedAnthropicChatModel.createRequest() END ===" } }
        } else {
            log.debug { "Cache disabled or strategy not CONVERSATION_HISTORY, returning baseRequest as-is" }
            log.debug { "=== PromptCachingFixedAnthropicChatModel.createRequest() END ===" }
            baseRequest
        }
    }

    /**
     * Apply cache control to the last tool definition.
     * Tools are cached separately from messages and system.
     */
    private fun applyToolsCacheControl(
        tools: List<Tool>,
        cacheEligibilityResolver: CacheEligibilityResolver,
    ): List<Tool> {
        log.debug { "applyToolsCacheControl: processing ${tools.size} tools" }

        if (tools.isEmpty()) {
            return tools
        }

        // Apply cache control to the last tool
        val fixedTools = tools
            .mapIndexed { i, tool ->
                if (i == tools.lastIndex) {
                    log.debug { "Applying cache control to last tool (index $i)" }
                    val basisForLength = JsonParser.toJson(tool)
                    log.debug { "Tool basis length: ${basisForLength.length}" }
                    val cacheControl = cacheEligibilityResolver.resolveToolCacheControl()

                    cacheControl?.let {
                        log.debug { "Cache control resolved for tool: $it, calling useCacheBlock()" }
                        cacheEligibilityResolver.useCacheBlock()
                        tool.withCacheControl(it)
                    } ?: run {
                        log.debug { "Cache control returned null for tool, keeping as-is" }
                        tool
                    }
                } else {
                    log.debug { "Tool $i is not last, removing cache control" }
                    tool.withoutCacheControl()
                }
            }

        log.debug { "applyToolsCacheControl: completed, ${fixedTools.size} tools processed" }
        return fixedTools
    }

    /**
     * Add cache control to a tool.
     */
    private fun Tool.withCacheControl(cacheControl: ChatCompletionRequest.CacheControl): Tool {
        return Tool(this.name, this.description, this.inputSchema, cacheControl)
    }

    /**
     * Remove cache control from a tool.
     */
    private fun Tool.withoutCacheControl(): Tool {
        return Tool(this.name, this.description, this.inputSchema, null)
    }

    /**
     * Apply cache control to the last system block.
     * System blocks are cached separately from messages.
     */
    private fun applySystemCacheControl(
        systemBlocks: List<ContentBlock>,
        cacheEligibilityResolver: CacheEligibilityResolver,
    ): List<ContentBlock> {
        log.debug { "applySystemCacheControl: processing ${systemBlocks.size} system blocks" }

        if (systemBlocks.isEmpty()) {
            return systemBlocks
        }

        // Apply cache control to the last system block
        val fixedBlocks = systemBlocks
            .mapIndexed { i, block ->
                if (i == systemBlocks.lastIndex) {
                    log.debug { "Applying cache control to last system block (index $i)" }
                    val basisForLength = block.getBasisForLength()
                    log.debug { "System block basis length: ${basisForLength.length}" }
                    val cacheControl = cacheEligibilityResolver.resolve(MessageType.SYSTEM, basisForLength)

                    cacheControl?.let {
                        log.debug { "Cache control resolved for system block: $it, calling useCacheBlock()" }
                        cacheEligibilityResolver.useCacheBlock()
                        ContentBlock.from(block).cacheControl(it).build()
                    } ?: run {
                        log.debug { "Cache control returned null for system block, keeping as-is" }
                        block
                    }
                } else {
                    log.debug { "System block $i is not last, removing cache control" }
                    ContentBlock.from(block).cacheControl(null).build()
                }
            }

        log.debug { "applySystemCacheControl: completed, ${fixedBlocks.size} system blocks processed" }
        return fixedBlocks
    }

    /**
     * Apply cache control only to the last non-empty content block across all messages.
     *
     * This prevents wasteful breakpoint usage on intermediate messages.
     */
    private fun applyCacheControl(
        messages: List<AnthropicMessage>,
        cacheEligibilityResolver: CacheEligibilityResolver,
    ): List<AnthropicMessage> {
        log.debug { "applyFixedCacheControl: processing ${messages.size} messages" }

        val lastMessageWithContent = messages
            .lastOrNull { it.content?.isNotEmpty() == true }
            ?: return messages.also { log.debug { "No message with content found, returning as-is" } }

        val lastThreadMessageIndex = messages.indexOf(lastMessageWithContent)
        log.debug { "Last message with content at index $lastThreadMessageIndex (role=${lastMessageWithContent.role})" }

        // Rebuild messages with cache control applied only to the last block
        val fixedMessages = messages
            .mapIndexed { i, msg ->
                when {
                    i == lastThreadMessageIndex -> {
                        log.debug { "Applying cache control to message at index $i (role=${msg.role})" }
                        msg.withCacheControlOnLastBlock(cacheEligibilityResolver)
                    }

                    else -> {
                        log.debug { "Removing cache control from message at index $i (role=${msg.role})" }
                        msg.withoutCacheControl()
                    }
                }
            }

        log.debug { "applyFixedCacheControl: completed, ${fixedMessages.size} messages processed" }
        return fixedMessages
    }

    /**
     * Apply cache control to the last content block of this message.
     */
    private fun AnthropicMessage.withCacheControlOnLastBlock(
        resolver: CacheEligibilityResolver,
    ): AnthropicMessage {
        log.debug { "withCacheControlOnLastBlock: processing message with ${content?.size ?: 0} content blocks" }

        val fixedContent = content?.mapIndexed { j, block ->
            if (j == content!!.lastIndex) {
                log.debug { "Processing last content block (index $j)" }
                val basisForLength = block.getBasisForLength()
                log.debug { "Content basis length: ${basisForLength.length}" }
                val cacheControl = resolver.resolve(MessageType.USER, basisForLength)

                cacheControl?.let {
                    log.debug { "Cache control resolved: $it, calling useCacheBlock()" }
                    resolver.useCacheBlock()
                    ContentBlock.from(block).cacheControl(it).build()
                } ?: run {
                    log.debug { "Cache control returned null, block unchanged" }
                    block
                }
            } else {
                log.debug { "Content block $j is not last, keeping as-is" }
                block
            }
        } ?: emptyList()

        log.debug { "withCacheControlOnLastBlock: completed" }
        return AnthropicMessage(fixedContent, role)
    }

    /**
     * Remove cache control from this message (for intermediate messages).
     */
    private fun AnthropicMessage.withoutCacheControl(): AnthropicMessage {
        val cleanedContent = content?.map { ContentBlock.from(it).cacheControl(null).build() }
        return AnthropicMessage(cleanedContent, role)
    }

    /**
     * Get a text basis for cache eligibility checking.
     */
    private fun ContentBlock.getBasisForLength(): String = when {
        text != null -> text
        input != null -> JsonParser.toJson(input)
        content != null -> JsonParser.toJson(content)
        else -> ""
    }
}
