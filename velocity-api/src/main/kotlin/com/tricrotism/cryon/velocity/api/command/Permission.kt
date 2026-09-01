package com.tricrotism.cryon.velocity.api.command

/**
 * Permission gate, on the class (whole command) or a single method.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Permission(val value: String)
