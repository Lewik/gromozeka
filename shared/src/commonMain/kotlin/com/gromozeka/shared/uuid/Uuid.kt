package com.gromozeka.shared.uuid

/**
 * Generates UUID v7 (RFC 9562) - time-ordered universally unique identifier.
 *
 * Format: unix_ts_ms(48 bits) + ver(4) + rand(12) + var(2) + rand(62)
 *
 * Properties:
 * - Lexicographically sortable by creation time
 * - Thread-safe generation
 * - Monotonic ordering guarantee
 * - Handles clock skew
 *
 * Example: "018e8c16-4e9a-7490-8000-123456789abc"
 */
expect fun uuid7(): String
