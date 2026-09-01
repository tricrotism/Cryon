package com.tricrotism.cryon.paper.api.bedrock

/**
 * A submitted custom form, keyed by [FormField.id]. Accessors return null on a type mismatch.
 */
class FormResponse(private val values: Map<String, Any?>) {
    fun text(id: String): String? = values[id] as? String
    fun toggle(id: String): Boolean? = values[id] as? Boolean
    fun choice(id: String): String? = values[id] as? String
    fun number(id: String): Float? = values[id] as? Float
    fun raw(): Map<String, Any?> = values
}
