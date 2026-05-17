package klog

import kotlinx.datetime.Clock

object KLoggers {
    fun logger(name: String): KLogger = KLogger(name)

    fun logger(owner: Any): KLogger = KLogger(owner.toString().substringBefore('@').ifBlank { "Logger" })
}

class KLogger(
    private val name: String,
) {
    fun trace(message: String) = log("TRACE", message)
    fun trace(message: () -> String) = trace(message())

    fun debug(message: String) = log("DEBUG", message)
    fun debug(message: () -> String) = debug(message())

    fun info(message: String) = log("INFO", message)
    fun info(message: () -> String) = info(message())

    fun warn(message: String) = log("WARN", message)
    fun warn(message: () -> String) = warn(message())
    fun warn(error: Throwable, message: String) = warn("$message: ${error.message}")
    fun warn(error: Throwable, message: () -> String) = warn(error, message())

    fun error(message: String) = log("ERROR", message)
    fun error(message: () -> String) = error(message())
    fun error(error: Throwable, message: String) = error("$message: ${error.message}")
    fun error(error: Throwable, message: () -> String) = error(error, message())

    private fun log(level: String, message: String) {
        println("${Clock.System.now()} [$level] $name - $message")
    }
}
