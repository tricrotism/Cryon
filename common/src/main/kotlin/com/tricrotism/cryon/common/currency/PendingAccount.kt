package com.tricrotism.cryon.common.currency

import java.util.*

/**
 * One account with something owed to the database.
 */
class PendingAccount(val scope: String, val currency: String, val player: UUID)
