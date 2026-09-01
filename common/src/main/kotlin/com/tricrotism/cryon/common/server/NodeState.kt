package com.tricrotism.cryon.common.server

/**
 * Where an instance is in its lifecycle. Only [READY] instances accept routed players.
 */
enum class NodeState { STARTING, READY, DRAINING, STOPPING }
