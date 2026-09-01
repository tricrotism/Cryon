package com.tricrotism.cryon.geyser.api.command

/**
 * Marks a trailing `String` argument as greedy (consumes the rest of the input).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Greedy
