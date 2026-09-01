package com.tricrotism.cryon.velocity.api.command

/**
 * A command argument. [value] is the Brigadier argument name (required, Java doesn't retain
 * parameter names). [suggests], if set, names a public no-arg method returning `Collection<String>`
 * used for tab completion. Supported parameter types: `String`, `Int`, `Boolean`.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Arg(val value: String, val suggests: String = "")
