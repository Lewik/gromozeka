package klog

import com.gromozeka.shared.logging.GromozekaLogger
import com.gromozeka.shared.logging.GromozekaLogging

object KLoggers {
    fun logger(name: String): KLogger = KLogger(GromozekaLogging.logger(name))

    fun logger(owner: Any): KLogger = KLogger(GromozekaLogging.logger(owner))
}

class KLogger(
    private val delegate: GromozekaLogger,
) {
    fun trace(message: String) = delegate.trace(message)
    fun trace(message: () -> String) = delegate.trace(message)

    fun debug(message: String) = delegate.debug(message)
    fun debug(message: () -> String) = delegate.debug(message)

    fun info(message: String) = delegate.info(message)
    fun info(message: () -> String) = delegate.info(message)

    fun warn(message: String) = delegate.warn(message)
    fun warn(message: () -> String) = delegate.warn(message)
    fun warn(error: Throwable, message: String) = delegate.warn(error, message)
    fun warn(error: Throwable, message: () -> String) = delegate.warn(error, message)

    fun error(message: String) = delegate.error(message)
    fun error(message: () -> String) = delegate.error(message)
    fun error(error: Throwable, message: String) = delegate.error(error, message)
    fun error(error: Throwable, message: () -> String) = delegate.error(error, message)
}
