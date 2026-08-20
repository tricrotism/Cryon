package com.tricrotism.cryon.geyser

import org.geysermc.geyser.api.extension.ExtensionLogger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter

/**
 * Bridges Geyser's [ExtensionLogger] to the `org.slf4j.Logger` every `:common` service takes.
 *
 * Geyser hands an extension its own prefixed logger rather than an slf4j one, and `LoggerFactory`
 * would answer with whatever binding the standalone jar happens to carry, losing the `[cryon]`
 * prefix and the extension's debug switch. Routing through the handle Geyser gave us keeps loader
 * output in the same shape as the rest of Geyser's console.
 *
 * [ExtensionLogger] has no trace level, so trace folds into debug.
 */
class GeyserLoggerAdapter(private val delegate: ExtensionLogger) : LegacyAbstractLogger() {

    init {
        name = "Cryon"
    }

    override fun getFullyQualifiedCallerName(): String? = null

    override fun isTraceEnabled(): Boolean = delegate.isDebug
    override fun isDebugEnabled(): Boolean = delegate.isDebug
    override fun isInfoEnabled(): Boolean = true
    override fun isWarnEnabled(): Boolean = true
    override fun isErrorEnabled(): Boolean = true

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val message = MessageFormatter.basicArrayFormat(messagePattern, arguments)
        when (level) {
            Level.ERROR -> if (throwable != null) delegate.error(message, throwable) else delegate.error(message)
            Level.WARN -> delegate.warning(withThrowable(message, throwable))
            Level.INFO -> delegate.info(withThrowable(message, throwable))
            Level.DEBUG, Level.TRACE -> delegate.debug(withThrowable(message, throwable))
        }
    }

    private fun withThrowable(message: String, throwable: Throwable?): String =
        if (throwable == null) message else "$message: ${throwable.stackTraceToString()}"
}
