package com.gromozeka.domain.tool.web

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for brave_web_search tool.
 * 
 * @property query Search query string (required)
 * @property count Maximum number of results to return (default: 10, max: 20)
 * @property offset Skip first N results for pagination (default: 0)
 */
data class BraveWebSearchRequest(
    val query: String,
    val count: Int = 10,
    val offset: Int = 0
)

/**
 * Domain specification for web search via Brave Search API.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `brave_web_search`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Perform web searches using Brave Search API for general queries, news, articles,
 * and online content. Privacy-focused alternative to Google Search with comparable results.
 * 
 * ## Core Features
 * 
 * **Comprehensive web search:**
 * - General information queries
 * - Recent news and articles
 * - Technical documentation
 * - Blog posts and tutorials
 * - Product reviews and comparisons
 * 
 * **Privacy-focused:**
 * - No user tracking (Brave Search philosophy)
 * - No search history logging
 * - No personalized results (consistent outputs)
 * - Independent search index (not Google)
 * 
 * **Pagination support:**
 * - Control result count (1-20 per request)
 * - Offset for deep pagination
 * - Fetch more results incrementally
 * 
 * # When to Use
 * 
 * **Use brave_web_search when:**
 * - Need general information from the web
 * - Looking for recent news or articles
 * - Searching for documentation or tutorials
 * - Fact-checking or research
 * - Finding product information
 * - Need diverse web sources
 * 
 * **Don't use when:**
 * - Need local businesses → use `brave_local_search`
 * - Have specific URL → use `jina_read_url`
 * - Need code repository → search GitHub directly
 * - Need academic papers → use specialized search
 * 
 * # Parameters
 * 
 * ## query: String (required)
 * 
 * Search query in natural language or keywords.
 * 
 * **Query types:**
 * - **Simple:** `"Kotlin programming language"`
 * - **Phrase:** `"Spring AI framework documentation"`
 * - **Question:** `"How to implement vector search?"`
 * - **Technical:** `"Neo4j Cypher query syntax"`
 * - **Recent:** `"latest Kotlin release 2024"`
 * 
 * **Query tips:**
 * - Be specific (better results)
 * - Use quotes for exact phrases
 * - Add year for recent content
 * - Include technology names explicitly
 * 
 * ## count: Int (optional, default: 10)
 * 
 * Maximum number of results to return.
 * 
 * **Range:** 1-20 (Brave API limit)
 * 
 * **Recommended values:**
 * - `5` - Quick answer lookup
 * - `10` (default) - Standard search
 * - `20` - Comprehensive research
 * 
 * **Note:** More results = more data to process in LLM context
 * 
 * ## offset: Int (optional, default: 0)
 * 
 * Skip first N results for pagination.
 * 
 * **Use cases:**
 * - Pagination: Get results 11-20 with offset=10
 * - Deep search: Find less popular sources
 * - Exhaustive research: Retrieve hundreds of results incrementally
 * 
 * **Example pagination:**
 * ```
 * Page 1: offset=0, count=10  (results 1-10)
 * Page 2: offset=10, count=10 (results 11-20)
 * Page 3: offset=20, count=10 (results 21-30)
 * ```
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with search results:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "success": true,
 *   "results": [
 *     {
 *       "title": "Kotlin Programming Language",
 *       "url": "https://kotlinlang.org/",
 *       "description": "Kotlin is a modern programming language that makes developers happier. Open source forever and free to use."
 *     },
 *     {
 *       "title": "Kotlin Documentation",
 *       "url": "https://kotlinlang.org/docs/home.html",
 *       "description": "Learn Kotlin with official documentation, tutorials, and guides."
 *     }
 *   ],
 *   "error": ""
 * }
 * ```
 * 
 * **Response structure:**
 * - `success: Boolean` - Whether search succeeded
 * - `results: List<Result>` - Search results (empty if none found)
 * - `error: String` - Error message (empty on success)
 * 
 * **Result fields:**
 * - `title: String` - Page title
 * - `url: String` - Page URL
 * - `description: String` - Snippet/summary (may be empty)
 * 
 * ## Error Response (API disabled)
 * 
 * ```json
 * {
 *   "success": false,
 *   "results": [],
 *   "error": "Brave Search is disabled or API key is not configured"
 * }
 * ```
 * 
 * ## Error Response (API error)
 * 
 * ```json
 * {
 *   "success": false,
 *   "results": [],
 *   "error": "HTTP 429: Rate limit exceeded"
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | API disabled | Brave Search not enabled in settings | Enable in configuration |
 * | API key missing | No API key configured | Add Brave API key |
 * | Rate limit | Too many requests | Wait and retry, upgrade plan |
 * | Invalid query | Empty or malformed query | Provide valid query string |
 * | Network error | Connection failed | Check internet, retry |
 * | HTTP 4xx/5xx | Brave API error | Check API status, retry later |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Simple Information Query
 * 
 * **Use case:** User asks "What is Kotlin?"
 * 
 * ```json
 * {
 *   "tool": "brave_web_search",
 *   "parameters": {
 *     "query": "Kotlin programming language"
 *   }
 * }
 * ```
 * 
 * **Result:** 10 results about Kotlin
 * 
 * ## Example 2: Recent News
 * 
 * **Use case:** Find latest information
 * 
 * ```json
 * {
 *   "tool": "brave_web_search",
 *   "parameters": {
 *     "query": "Spring AI framework latest release 2024",
 *     "count": 5
 *   }
 * }
 * ```
 * 
 * **Result:** 5 recent articles about Spring AI
 * 
 * ## Example 3: Documentation Search
 * 
 * **Use case:** Find technical documentation
 * 
 * ```json
 * {
 *   "tool": "brave_web_search",
 *   "parameters": {
 *     "query": "Neo4j Cypher query tutorial",
 *     "count": 10
 *   }
 * }
 * ```
 * 
 * **Result:** 10 documentation and tutorial links
 * 
 * ## Example 4: Pagination for Deep Research
 * 
 * **Use case:** Comprehensive research on topic
 * 
 * ```json
 * [
 *   {
 *     "tool": "brave_web_search",
 *     "parameters": {
 *       "query": "vector search databases comparison",
 *       "count": 20,
 *       "offset": 0
 *     }
 *   },
 *   {
 *     "tool": "brave_web_search",
 *     "parameters": {
 *       "query": "vector search databases comparison",
 *       "count": 20,
 *       "offset": 20
 *     }
 *   }
 * ]
 * ```
 * 
 * **Result:** 40 results total (2 pages)
 * 
 * ## Example 5: Exact Phrase Search
 * 
 * **Use case:** Find specific phrase
 * 
 * ```json
 * {
 *   "tool": "brave_web_search",
 *   "parameters": {
 *     "query": "\"domain-driven design\" kotlin examples"
 *   }
 * }
 * ```
 * 
 * **Result:** Results containing exact phrase "domain-driven design"
 * 
 * # Common Patterns
 * 
 * ## Pattern: Research Workflow
 * 
 * Comprehensive information gathering:
 * 
 * 1. `brave_web_search("Topic overview", count=5)` - Get general understanding
 * 2. Select most promising URLs
 * 3. `jina_read_url(selectedUrl)` - Read detailed content
 * 4. `build_memory_from_text(content)` - Save to knowledge graph
 * 
 * Result: Deep research with knowledge retention
 * 
 * ## Pattern: Fact Verification
 * 
 * Verify information from multiple sources:
 * 
 * 1. `brave_web_search("Claim to verify", count=10)`
 * 2. Check if multiple credible sources confirm
 * 3. Read conflicting sources with `jina_read_url`
 * 4. Report confidence level to user
 * 
 * Result: Fact-checked information
 * 
 * ## Pattern: Find Then Read
 * 
 * Two-step content access:
 * 
 * 1. `brave_web_search(query)` - Find relevant pages
 * 2. Review result titles and descriptions
 * 3. `jina_read_url(result.url)` - Read most relevant page
 * 
 * Result: Efficient content discovery and consumption
 * 
 * # Transactionality
 * 
 * **Read-only operation:**
 * - No side effects
 * - Safe to call multiple times
 * - No state changes
 * 
 * **Idempotency:**
 * - NOT strictly idempotent (results may change over time)
 * - Same query today vs tomorrow may yield different results
 * - Web content evolves
 * 
 * **Caching:**
 * - Results could be cached short-term (minutes)
 * - Consider cache for identical queries in same session
 * - Don't cache for time-sensitive queries
 * 
 * # Performance Characteristics
 * 
 * - **Execution time:** 200-1000ms (network + API processing)
 * - **Rate limits:** Depends on Brave API plan (typically 1 req/sec free tier)
 * - **Result size:** ~1-5KB per result (varies by description length)
 * 
 * **Cost considerations:**
 * - Brave Search has free tier with limits
 * - Paid plans for higher rate limits
 * - Count impacts response size (more results = more data)
 * 
 * # Search Quality Tips
 * 
 * **Better queries:**
 * - ✅ "Spring AI ChatModel interface documentation"
 * - ❌ "spring ai" (too vague)
 * 
 * **Use operators:**
 * - Quotes: `"exact phrase"`
 * - Site: `site:kotlinlang.org` (search specific site)
 * - Minus: `-tutorial` (exclude tutorials)
 * 
 * **Temporal queries:**
 * - Add year: "Kotlin 2024"
 * - Use "latest", "recent", "new"
 * 
 * # Privacy Benefits
 * 
 * **Why Brave Search:**
 * - Independent index (not relying on Google/Bing)
 * - No user profiling
 * - No search history tracking
 * - Consistent results (not personalized)
 * - Privacy-preserving by design
 * 
 * **For AI assistants:**
 * - User's searches not tracked
 * - No cross-session correlation
 * - Transparent search behavior
 * 
 * # Related Tools
 * 
 * - **brave_local_search** - For local businesses and places
 * - **jina_read_url** - Read content from search result URLs
 * - **build_memory_from_text** - Save search findings to knowledge graph
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.web.BraveWebSearchTool
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.WebSearchService.searchWeb (when created)
 */
interface BraveWebSearchTool : Tool<BraveWebSearchRequest, Map<String, Any>> {
    
    override val name: String
        get() = "brave_web_search"
    
    override val description: String
        get() = """
            Performs a web search using the Brave Search API, ideal for general queries, news, articles, and online content. Use this for broad information gathering, recent events, or when you need diverse web sources. Supports pagination, content filtering, and freshness controls. Maximum 20 results per request, with offset for pagination. 
        """.trimIndent()
    
    override val requestType: Class<BraveWebSearchRequest>
        get() = BraveWebSearchRequest::class.java
    
    override fun execute(request: BraveWebSearchRequest, context: ToolContext?): Map<String, Any>
}
