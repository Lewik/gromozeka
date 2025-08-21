package com.gromozeka.bot.utils

import java.io.IOException
import java.net.ServerSocket
import kotlin.random.Random

/**
 * Finds random available port in specified range (default: dynamic/private ports 49152-65535)
 */
fun findRandomAvailablePort(
    rangeStart: Int = 49152,
    rangeEnd: Int = 65535,
    maxAttempts: Int = 10
): Int {
    repeat(maxAttempts) {
        val port = Random.nextInt(rangeStart, rangeEnd + 1)

        if (isPortAvailable(port)) {
            return port
        }
    }

    throw IllegalStateException("Could not find available port after $maxAttempts attempts in range $rangeStart-$rangeEnd")
}

/**
 * Checks if port is available by trying to bind to it
 */
fun isPortAvailable(port: Int): Boolean {
    return try {
        ServerSocket(port).use { true }
    } catch (e: IOException) {
        false
    }
}