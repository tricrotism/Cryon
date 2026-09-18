package com.tricrotism.cryon.common.experiment

/**
 * One arm of an [Experiment] and how much of the population it gets.
 *
 * [weight] is relative, not a percentage: `50/50`, `1/1` and `7/7` are the same split, and `90/10` is
 * a canary. Nothing has to add up to a hundred, which matters because adding a third arm to a running
 * two-arm experiment would otherwise mean rewriting the other two.
 *
 * [id] is held to lowercase letters, digits and underscores. It travels through three places that all
 * assume it is boring - the `id:weight,id:weight` column, the sync payload, and whatever dashboard
 * eventually groups exposures by arm - and a comma or a separator inside one would corrupt a stored
 * definition rather than fail anywhere a person would see it.
 */
data class Arm(val id: String, val weight: Int) {

    init {
        require(id.isNotBlank()) { "an arm needs an id" }
        require(id.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }) {
            "arm id '$id' must be lowercase letters, digits and underscores"
        }
        require(weight > 0) { "arm $id has weight $weight; a zero-weight arm is an arm you deleted" }
    }
}
