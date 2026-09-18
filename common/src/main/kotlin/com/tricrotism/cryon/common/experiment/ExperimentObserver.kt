package com.tricrotism.cryon.common.experiment

import java.util.*

/**
 * Notified when a player is **shown** something an experiment decided.
 *
 * The exposure is the denominator: an arm's result means nothing without knowing how many people
 * actually saw it, and a player assigned to an arm they never reached is not a data point. So this
 * fires from [Experiments.expose] - the display call - and never from [Experiments.arm], which only
 * answers the question.
 *
 * Called on whatever thread is doing the displaying. Record and return.
 */
fun interface ExperimentObserver {

    fun exposed(subject: UUID, experiment: String, arm: String, surface: String)
}
