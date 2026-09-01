package com.tricrotism.cryon.geyser.api.command

/**
 * A command argument. [value] is the display name used in usage lines (required, Java doesn't retain
 * parameter names). [suggests], if set, names a public no-arg method returning `Collection<String>`;
 * Geyser offers no per-argument completion hook, so those values are used to name the accepted
 * choices when an argument is rejected. Supported parameter types: `String`, `Int`, `Boolean`.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Arg(val value: String, val suggests: String = "")
