package com.gromozeka.domain.tool.web

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for jina_read_url tool.
 * 
 * @property url URL to read and convert to Markdown (required)
 */
data class JinaReadUrlRequest(
    val url: String
)

/**
 * Domain specification for URL content extraction via Jina Reader API.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `jina_read_url`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Convert any web URL to clean, LLM-friendly Markdown using Jina Reader API.
 * Extracts main content from web pages while removing ads, navigation, and other noise.
 * 
 * ## Core Features
 * 
 * **Content extraction:**
 * - Main article/content only
 * - Removes ads, sidebars, navigation
 * - Removes popups and tracking scripts
 * - Preserves text structure and formatting
 * - Keeps important links and images
 * 
 * **Markdown conversion:**
 * - Clean Markdown output
 * - Preserves headings, lists, code blocks
 * - Optimized for LLM consumption
 * - Readable by humans too
 * - Consistent formatting
 * 
 * **Wide compatibility:**
 * - Documentation sites
 * - Blog posts and articles
 * - News websites
 * - GitHub repositories
 * - Technical tutorials
 * 
 * # When to Use
 * 
 * **Use jina_read_url when:**
 * - Need to read article or blog post content
 * - Analyzing documentation pages
 * - Extracting information from search results
 * - Reading GitHub README files
 * - Processing web pages for knowledge extraction
 * - Converting HTML to structured Markdown
 * 
 * **Don't use when:**
 * - Need to search for URLs → use `brave_web_search` first
 * - URL contains only images/videos → won't extract much text
 * - Need interactive content → Jina only extracts static content
 * - URL requires authentication → Jina can't access private pages
 * - URL is PDF → may work but quality varies
 * 
 * # Parameters
 * 
 * ## url: String (required)
 * 
 * Full URL of the web page to read and convert.
 * 
 * **Supported URL types:**
 * - **Documentation:** `https://kotlinlang.org/docs/getting-started.html`
 * - **Blog posts:** `https://blog.jetbrains.com/kotlin/2024/...`
 * - **Articles:** `https://medium.com/@author/article-title`
 * - **GitHub:** `https://github.com/user/repo/blob/main/README.md`
 * - **News:** `https://techcrunch.com/2024/...`
 * 
 * **URL requirements:**
 * - Must be valid HTTP/HTTPS URL
 * - Must be publicly accessible (no auth required)
 * - Should contain text content (not just images)
 * 
 * **URL tips:**
 * - Use full URL including protocol (https://)
 * - Direct links to articles work best
 * - Homepage URLs may return navigation instead of content
 * - Avoid URLs with paywalls
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with extracted Markdown content:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "success": true,
 *   "content": "# Kotlin Programming Language\n\nKotlin is a modern programming language that makes developers happier.\n\n## Features\n\n- Concise syntax\n- Null safety\n- Multiplatform support\n\n[Learn more](https://kotlinlang.org/docs/)",
 *   "error": ""
 * }
 * ```
 * 
 * **Response structure:**
 * - `success: Boolean` - Whether extraction succeeded
 * - `content: String` - Extracted Markdown content
 * - `error: String` - Error message (empty on success)
 * 
 * **Content structure:**
 * - Clean Markdown format
 * - Headings (`#`, `##`, `###`)
 * - Lists (`-`, `*`, `1.`)
 * - Code blocks (` ``` `)
 * - Links (`[text](url)`)
 * - Emphasis (`**bold**`, `*italic*`)
 * 
 * ## Error Response (API disabled)
 * 
 * ```json
 * {
 *   "success": false,
 *   "content": "",
 *   "error": "Jina Reader is disabled or API key is not configured"
 * }
 * ```
 * 
 * ## Error Response (Invalid URL)
 * 
 * ```json
 * {
 *   "success": false,
 *   "content": "",
 *   "error": "HTTP 404: Page not found"
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | API disabled | Jina Reader not enabled | Enable in configuration |
 * | API key missing | No API key configured | Add Jina API key |
 * | Invalid URL | URL malformed or inaccessible | Check URL validity |
 * | 404 Not Found | Page doesn't exist | Verify URL |
 * | 403 Forbidden | Access denied, auth required | Use publicly accessible URL |
 * | Network error | Connection failed | Check internet, retry |
 * | Empty content | Page has no text | Try different URL |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Read Documentation
 * 
 * **Use case:** Extract content from documentation page
 * 
 * ```json
 * {
 *   "tool": "jina_read_url",
 *   "parameters": {
 *     "url": "https://kotlinlang.org/docs/getting-started.html"
 *   }
 * }
 * ```
 * 
 * **Result:** Clean Markdown of Kotlin getting started guide
 * 
 * ## Example 2: Read Blog Post
 * 
 * **Use case:** Extract article content for analysis
 * 
 * ```json
 * {
 *   "tool": "jina_read_url",
 *   "parameters": {
 *     "url": "https://blog.jetbrains.com/kotlin/2024/kotlin-2-0-released/"
 *   }
 * }
 * ```
 * 
 * **Result:** Article text without ads and navigation
 * 
 * ## Example 3: Read GitHub README
 * 
 * **Use case:** Understand project from README
 * 
 * ```json
 * {
 *   "tool": "jina_read_url",
 *   "parameters": {
 *     "url": "https://github.com/spring-projects/spring-ai"
 *   }
 * }
 * ```
 * 
 * **Result:** README.md content in clean Markdown
 * 
 * ## Example 4: Extract Search Result Content
 * 
 * **Use case:** Follow up on web search to read full article
 * 
 * ```json
 * [
 *   {
 *     "tool": "brave_web_search",
 *     "parameters": {
 *       "query": "Spring AI streaming tutorial"
 *     }
 *   },
 *   {
 *     "tool": "jina_read_url",
 *     "parameters": {
 *       "url": "<first result URL>"
 *     }
 *   }
 * ]
 * ```
 * 
 * **Result:** Full tutorial content from top search result
 * 
 * # Common Patterns
 * 
 * ## Pattern: Search → Read → Remember
 * 
 * Comprehensive research workflow:
 * 
 * 1. `brave_web_search("Topic")` - Find relevant pages
 * 2. `jina_read_url(result.url)` - Read most relevant page
 * 3. `build_memory_from_text(content)` - Save to knowledge graph
 * 
 * Result: Research with persistent knowledge
 * 
 * ## Pattern: Multi-Source Reading
 * 
 * Read multiple sources on same topic:
 * 
 * 1. Search for topic, get 5 URLs
 * 2. Read each URL with `jina_read_url`
 * 3. Compare information across sources
 * 4. Synthesize comprehensive answer
 * 
 * Result: Well-researched response
 * 
 * ## Pattern: Documentation Deep Dive
 * 
 * Explore documentation structure:
 * 
 * 1. `jina_read_url(docHomepage)` - Get overview
 * 2. Extract links to specific topics
 * 3. `jina_read_url(specificTopic)` - Read details
 * 4. Build understanding incrementally
 * 
 * Result: Deep technical knowledge
 * 
 * # Transactionality
 * 
 * **Read-only operation:**
 * - No side effects
 * - Safe to call multiple times
 * - No state changes
 * 
 * **Idempotency:**
 * - Generally idempotent for static pages
 * - Dynamic content may change between calls
 * - News sites update frequently
 * 
 * **Caching:**
 * - Can cache results for static content
 * - Documentation rarely changes (hours/days)
 * - News/blogs change more often (minutes/hours)
 * 
 * # Performance Characteristics
 * 
 * - **Execution time:** 500-3000ms (varies by page size)
 * - **Rate limits:** Depends on Jina API plan
 * - **Content size:** Typically 5-50KB Markdown output
 * 
 * **Size considerations:**
 * - Large articles: 10-50KB Markdown
 * - Medium pages: 5-10KB Markdown
 * - Small pages: 1-5KB Markdown
 * - Very large pages (>100KB): may timeout
 * 
 * # Content Extraction Quality
 * 
 * **What Jina Reader does well:**
 * - ✅ Removes ads and tracking
 * - ✅ Extracts main article content
 * - ✅ Preserves headings and structure
 * - ✅ Keeps important links
 * - ✅ Cleans HTML clutter
 * - ✅ Converts to readable Markdown
 * 
 * **Limitations:**
 * - ❌ May miss some content in complex layouts
 * - ❌ Doesn't execute JavaScript (sees initial HTML)
 * - ❌ Can't access content behind paywalls
 * - ❌ May include some navigation if not clearly separated
 * - ❌ Image descriptions may be limited
 * 
 * # Markdown Output Format
 * 
 * **Typical structure:**
 * ```markdown
 * # Main Heading
 * 
 * Introduction paragraph with **bold** and *italic* text.
 * 
 * ## Section Heading
 * 
 * - List item 1
 * - List item 2
 * 
 * ### Subsection
 * 
 * Code example:
 * ```kotlin
 * fun example() {
 *     println("Hello")
 * }
 * ```
 * 
 * [Link to documentation](https://example.com)
 * ```
 * 
 * **Preserved elements:**
 * - Headings (H1-H6)
 * - Paragraphs
 * - Lists (ordered and unordered)
 * - Code blocks (with language hints when available)
 * - Links
 * - Bold/italic emphasis
 * - Blockquotes
 * 
 * # Use Cases by Content Type
 * 
 * **Technical documentation:**
 * - API references → ✅ Excellent
 * - Tutorials → ✅ Excellent
 * - Getting started guides → ✅ Excellent
 * - Code examples → ✅ Good (preserved in code blocks)
 * 
 * **Blog posts/Articles:**
 * - Technical articles → ✅ Excellent
 * - News articles → ✅ Good
 * - Opinion pieces → ✅ Good
 * - Paywalled content → ❌ Not accessible
 * 
 * **GitHub:**
 * - README files → ✅ Excellent
 * - Wiki pages → ✅ Good
 * - Issue comments → ⚠️ Mixed (depends on page structure)
 * 
 * **Other:**
 * - Product pages → ⚠️ Mixed (may include too much navigation)
 * - Social media → ❌ Poor (requires login, dynamic content)
 * - Forums → ⚠️ Mixed (may extract too much UI)
 * 
 * # Privacy Benefits
 * 
 * **What Jina Reader provides:**
 * - Removes tracking scripts
 * - Strips analytics code
 * - No cookies or session tracking
 * - Clean content delivery
 * 
 * **Privacy note:**
 * - Jina Reader service sees the URLs you request
 * - Consider privacy policy for sensitive content
 * - Generally privacy-friendly service
 * 
 * # Error Handling Strategy
 * 
 * **Graceful degradation:**
 * 1. Try `jina_read_url(url)`
 * 2. If error: explain to user
 * 3. Suggest alternatives (try different URL, use web search)
 * 
 * **Common fixes:**
 * - 404 error → Check URL spelling
 * - Empty content → Try different page on same site
 * - Timeout → Retry or try shorter article
 * - Paywall → Search for free alternative source
 * 
 * # Related Tools
 * 
 * - **brave_web_search** - Find URLs to read
 * - **brave_local_search** - Find local business websites
 * - **build_memory_from_text** - Save extracted content to knowledge graph
 * - **add_memory_link** - Link URL to knowledge entities
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.web.JinaReadUrlTool
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.WebSearchService.readUrl (when created)
 */
interface JinaReadUrlTool : Tool<JinaReadUrlRequest, Map<String, Any>> {
    
    override val name: String
        get() = "jina_read_url"
    
    override val description: String
        get() = """
            Convert any URL to LLM-friendly Markdown using Jina Reader API. Extracts clean, structured content from web pages, removing ads, navigation, and other noise. Best for:
            - Reading documentation, articles, blog posts
            - Extracting main content from complex web pages
            - Converting HTML to Markdown for further processing
            
            Simply provide the URL and get back clean Markdown content optimized for LLM consumption.
        """.trimIndent()
    
    override val requestType: Class<JinaReadUrlRequest>
        get() = JinaReadUrlRequest::class.java
    
    override fun execute(request: JinaReadUrlRequest, context: ToolContext?): Map<String, Any>
}
