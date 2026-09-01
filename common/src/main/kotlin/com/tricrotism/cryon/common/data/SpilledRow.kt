package com.tricrotism.cryon.common.data

/**
 * One staged row as it would be bound to a statement.
 *
 * @param values the codec's values in column order
 * @param version the row version its guarded update writes against
 */
class SpilledRow(val id: String, val values: Array<out Any?>, val version: Long)
