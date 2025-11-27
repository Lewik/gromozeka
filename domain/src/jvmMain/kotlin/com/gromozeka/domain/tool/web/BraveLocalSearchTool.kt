package com.gromozeka.domain.tool.web

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for brave_local_search tool.
 * 
 * @property query Local search query (e.g., "pizza near me", "hotels in Paris")
 * @property count Maximum number of results to return (default: 5, max: 20)
 */
data class BraveLocalSearchRequest(
    val query: String,
    val count: Int = 5
)

/**
 * Domain specification for local business search via Brave Local Search API.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `brave_local_search`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Search for local businesses, places, and services using Brave's Local Search API.
 * Returns detailed information about physical locations including addresses, ratings,
 * contact information, and opening hours.
 * 
 * ## Core Features
 * 
 * **Local business discovery:**
 * - Restaurants, cafes, hotels
 * - Shops and services
 * - Offices and businesses
 * - Tourist attractions
 * - Healthcare facilities
 * 
 * **Rich business information:**
 * - Business name and address
 * - Phone numbers
 * - Ratings and review counts
 * - Opening hours (when available)
 * - Distance from user location
 * 
 * **Location-aware:**
 * - Understands "near me" queries
 * - Supports city/region specifications
 * - Returns local results based on query context
 * 
 * # When to Use
 * 
 * **Use brave_local_search when:**
 * - Query implies physical location ("near me", "in [city]")
 * - Looking for businesses, restaurants, services
 * - Need contact information (phone, address)
 * - Want ratings and reviews
 * - Planning visits or trips
 * 
 * **Don't use when:**
 * - Need general web information → use `brave_web_search`
 * - Have specific business URL → use `jina_read_url`
 * - Looking for online-only services → use `brave_web_search`
 * - Need product information (not place) → use `brave_web_search`
 * 
 * # Parameters
 * 
 * ## query: String (required)
 * 
 * Local search query describing what and optionally where to search.
 * 
 * **Query patterns:**
 * - **Type + Location:** `"coffee shops in Seattle"`
 * - **Near me:** `"pizza near me"`
 * - **Specific business:** `"Starbucks San Francisco"`
 * - **Service + Area:** `"dentist downtown Portland"`
 * - **Category:** `"italian restaurants Manhattan"`
 * 
 * **Query tips:**
 * - Include location for better results
 * - Be specific about business type
 * - Use common category names (restaurant, hotel, etc.)
 * - Can use "near me" (location inferred)
 * 
 * ## count: Int (optional, default: 5)
 * 
 * Maximum number of local results to return.
 * 
 * **Range:** 1-20 (typical)
 * 
 * **Recommended values:**
 * - `3-5` - Quick recommendations
 * - `5` (default) - Standard local search
 * - `10-20` - Comprehensive list
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with local business results:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "success": true,
 *   "results": [
 *     {
 *       "title": "Blue Bottle Coffee",
 *       "address": "123 Main St, San Francisco, CA 94103",
 *       "phone": "+1-415-555-0123",
 *       "rating": 4.5
 *     },
 *     {
 *       "title": "Philz Coffee",
 *       "address": "456 Market St, San Francisco, CA 94102",
 *       "phone": "+1-415-555-0456",
 *       "rating": 4.7
 *     }
 *   ],
 *   "error": ""
 * }
 * ```
 * 
 * **Response structure:**
 * - `success: Boolean` - Whether search succeeded
 * - `results: List<LocalResult>` - Local business results
 * - `error: String` - Error message (empty on success)
 * 
 * **LocalResult fields:**
 * - `title: String` - Business name
 * - `address: String` - Full address (may be empty)
 * - `phone: String` - Phone number (may be empty)
 * - `rating: Double` - User rating 0.0-5.0 (may be 0.0)
 * 
 * ## Success Response (No Local Results)
 * 
 * **Fallback behavior:** Automatically falls back to web search if no local results found.
 * 
 * ```json
 * {
 *   "success": true,
 *   "results": [
 *     {
 *       "title": "Coffee Brewing Guide",
 *       "url": "https://example.com/coffee",
 *       "description": "How to brew the perfect coffee..."
 *     }
 *   ],
 *   "error": "No local results found, showing web results"
 * }
 * ```
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
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | API disabled | Brave Search not enabled | Enable in configuration |
 * | API key missing | No API key configured | Add Brave API key |
 * | No results | Query too specific or location unclear | Broaden query, add location |
 * | Rate limit | Too many requests | Wait and retry |
 * | Network error | Connection failed | Check internet, retry |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Find Nearby Businesses
 * 
 * **Use case:** User asks "Where can I get coffee near me?"
 * 
 * ```json
 * {
 *   "tool": "brave_local_search",
 *   "parameters": {
 *     "query": "coffee shops near me"
 *   }
 * }
 * ```
 * 
 * **Result:** 5 nearby coffee shops with addresses and ratings
 * 
 * ## Example 2: Search in Specific City
 * 
 * **Use case:** Planning trip, need restaurants
 * 
 * ```json
 * {
 *   "tool": "brave_local_search",
 *   "parameters": {
 *     "query": "italian restaurants in Rome",
 *     "count": 10
 *   }
 * }
 * ```
 * 
 * **Result:** 10 Italian restaurants in Rome
 * 
 * ## Example 3: Find Specific Business
 * 
 * **Use case:** Locate specific store location
 * 
 * ```json
 * {
 *   "tool": "brave_local_search",
 *   "parameters": {
 *     "query": "Apple Store San Francisco",
 *     "count": 3
 *   }
 * }
 * ```
 * 
 * **Result:** Apple Store locations in SF with addresses
 * 
 * ## Example 4: Service Category Search
 * 
 * **Use case:** Need professional service
 * 
 * ```json
 * {
 *   "tool": "brave_local_search",
 *   "parameters": {
 *     "query": "dentist downtown Seattle"
 *   }
 * }
 * ```
 * 
 * **Result:** Dental offices in downtown Seattle
 * 
 * # Common Patterns
 * 
 * ## Pattern: Location Recommendations
 * 
 * Help user find places to visit:
 * 
 * 1. `brave_local_search("restaurants in Paris", count=10)` - Get options
 * 2. Filter by rating (>4.0)
 * 3. Present top recommendations with addresses
 * 4. User selects, you provide directions/details
 * 
 * Result: Curated recommendations
 * 
 * ## Pattern: Multi-Category Search
 * 
 * Find different types of places:
 * 
 * 1. `brave_local_search("hotels in Barcelona")`
 * 2. `brave_local_search("restaurants in Barcelona")`
 * 3. `brave_local_search("tourist attractions in Barcelona")`
 * 
 * Result: Comprehensive trip planning
 * 
 * ## Pattern: Fallback to Web Search
 * 
 * Handle queries that aren't purely local:
 * 
 * 1. Try `brave_local_search(query)`
 * 2. If no local results (check results.length == 0)
 * 3. Fall back to `brave_web_search(query)`
 * 
 * Result: Always get relevant results
 * 
 * # Transactionality
 * 
 * **Read-only operation:**
 * - No side effects
 * - Safe to call multiple times
 * - No state changes
 * 
 * **Idempotency:**
 * - Results may vary (businesses open/close, ratings change)
 * - Generally consistent for short time periods
 * - Location data relatively stable
 * 
 * **Caching:**
 * - Can cache results short-term (hours)
 * - Business info changes slowly
 * - Ratings update gradually
 * 
 * # Performance Characteristics
 * 
 * - **Execution time:** 200-1000ms (network + API processing)
 * - **Rate limits:** Same as brave_web_search
 * - **Result size:** ~1-3KB per result
 * 
 * **Comparison to web search:**
 * - Local search: Returns 5-10 results typically
 * - Web search: Returns 10-20 results typically
 * - Local results more focused (higher precision)
 * 
 * # Location Detection
 * 
 * **How location is determined:**
 * - Explicit in query: "in Seattle", "San Francisco"
 * - "Near me": Uses IP-based geolocation (approximate)
 * - Implicit: May infer from conversation context
 * 
 * **Location accuracy:**
 * - Explicit city names: High accuracy
 * - "Near me": City/region level (not GPS precise)
 * - No location: May return generic results
 * 
 * # Result Quality
 * 
 * **Business information completeness:**
 * - Name: Always present
 * - Address: Usually present (95%+)
 * - Phone: Often present (70%+)
 * - Rating: Sometimes present (50%+)
 * - Hours: Rarely in API response (use web search)
 * 
 * **Rating interpretation:**
 * - 0.0: No rating data
 * - 1.0-3.0: Below average
 * - 3.0-4.0: Average
 * - 4.0-4.5: Good
 * - 4.5-5.0: Excellent
 * 
 * # Fallback Behavior
 * 
 * **When no local results:**
 * - Tool automatically tries web search
 * - Returns web results with note in error field
 * - User gets useful information either way
 * 
 * **Example:**
 * Query: "coffee brewing methods" (not a local query)
 * → No local results
 * → Returns web results about brewing methods
 * → Error field: "No local results found, showing web results"
 * 
 * # Privacy Benefits
 * 
 * **Same as brave_web_search:**
 * - No location tracking beyond query
 * - No search history
 * - No user profiling
 * - Privacy-preserving by design
 * 
 * # Related Tools
 * 
 * - **brave_web_search** - For general web searches
 * - **jina_read_url** - Read business website details
 * - **add_memory_link** - Save favorite places to knowledge graph
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.web.BraveLocalSearchTool
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.WebSearchService.searchLocal (when created)
 */
interface BraveLocalSearchTool : Tool<BraveLocalSearchRequest, Map<String, Any>> {
    
    override val name: String
        get() = "brave_local_search"
    
    override val description: String
        get() = """
            Searches for local businesses and places using Brave's Local Search API. Best for queries related to physical locations, businesses, restaurants, services, etc. Returns detailed information including:
            - Business names and addresses
            - Ratings and review counts
            - Phone numbers and opening hours
            Use this when the query implies 'near me' or mentions specific locations. Automatically falls back to web search if no local results are found.
        """.trimIndent()
    
    override val requestType: Class<BraveLocalSearchRequest>
        get() = BraveLocalSearchRequest::class.java
    
    override fun execute(request: BraveLocalSearchRequest, context: ToolContext?): Map<String, Any>
}
