package com.tricrotism.cryon.common.data

import java.time.Instant

/**
 * What one table's spill is holding, for an operator to look at.
 *
 * @param rows -1 when the header could not be read, reported rather than acted on because looking at
 *   a spill must not change it
 */
class SpillSummary(val table: String, val rows: Int, val bytes: Long, val modified: Instant)
