package com.tricrotism.cryon.paper.api.command

/**
 * A handler method. Empty path = the root command; otherwise nested literals.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Subcommand(vararg val value: String)
