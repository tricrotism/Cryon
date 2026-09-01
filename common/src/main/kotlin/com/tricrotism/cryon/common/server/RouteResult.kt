package com.tricrotism.cryon.common.server

/**
 * Outcome of a routing request.
 */
sealed interface RouteResult {
    data class Sent(val nodeId: String) : RouteResult
    data object NoInstance : RouteResult
    data class Failed(val reason: String) : RouteResult
}
