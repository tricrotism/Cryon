package com.tricrotism.cryon.paper.api.command

/**
 * A reflected, display-ready view of one `@Command` class.
 */
data class CommandDescriptor(
    val name: String,
    val description: String,
    val aliases: List<String>,
    val permission: String?,
    /**
     * One line per handler method, e.g. `/f`, `/f create <name>`, `/f claim`.
     */
    val usages: List<String>,
)
