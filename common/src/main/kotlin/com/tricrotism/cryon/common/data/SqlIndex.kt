package com.tricrotism.cryon.common.data

/**
 * A secondary index on [columns] of a table, named [name].
 *
 * A type rather than a string because the two backends that take indexes separately and the one that
 * takes them inline need the same three facts spelled two different ways. See
 * [SqlDialect.inlineIndexes] and [SqlDialect.indexStatements].
 */
data class SqlIndex(val name: String, val columns: List<String>, val unique: Boolean = false)
