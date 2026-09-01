package com.tricrotism.cryon.common.data

import java.sql.ResultSet

/**
 * How one value maps onto its row. The feature still owns its schema and its columns; this only says
 * how to get a value in and out of them.
 *
 * [columns] excludes `id` and `version`, which every repository table carries and [Repository] owns.
 * [write] must emit values in exactly [columns] order, and [read] must consume them in that order
 * starting at index 1, the repository builds its `SELECT` from [columns], so the two line up as
 * long as neither is reordered independently.
 */
interface RowCodec<T : Any> {

    val columns: List<String>

    fun write(value: T): Array<out Any?>

    fun read(row: ResultSet): T
}
