package com.tricrotism.cryon.common.data

import java.sql.ResultSet

/**
 * One transaction's connection, handed to [Database.transaction]'s body.
 *
 * Deliberately smaller than [Database]: no `close`, no nested transaction, and nothing async. The
 * session is valid only for the duration of the call it was passed to, and holding one past that
 * point uses a connection that has already gone back to the pool.
 */
interface SqlSession {

    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T>

    fun update(sql: String, vararg params: Any?): Int

    /**
     * Run one statement once per row in [rows], as a single JDBC batch. Returns rows affected.
     *
     * The difference from a loop of [update] is round trips: a batch ships every parameter set in one
     * exchange with the backend instead of one each, which is the whole reason a write-behind flush
     * can afford to write two thousand dirty entries at a checkpoint. Each element of [rows] binds in
     * the statement's parameter order, exactly as [update]'s varargs do.
     *
     * An empty [rows] issues nothing and answers 0. Drivers may report `SUCCESS_NO_INFO` for a row
     * they applied without counting, so the total is a lower bound: compare it against zero, never
     * against `rows.size`.
     */
    fun batch(sql: String, rows: List<Array<out Any?>>): Int
}
