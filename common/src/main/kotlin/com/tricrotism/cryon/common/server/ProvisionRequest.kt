package com.tricrotism.cryon.common.server

/**
 * What to provision, and how hard to try.
 */
data class ProvisionRequest(
    /** The pool to look in and, if it comes to it, to create a node of. */
    val serverId: String,
    val selector: NodeSelector = NodeSelector.Available,
    /**
     * Whether to ask the orchestrator for a new node when nothing matches.
     *
     * False makes this a pure query over the registry, which is what a "send them if there's room"
     * path wants. Creating a shard because a hub happened to be full is rarely the intent.
     */
    val createIfMissing: Boolean = false,
    /** How long to wait for a newly created node to report `READY`. */
    val waitMillis: Long = DEFAULT_WAIT_MILLIS,
) {
    companion object {
        const val DEFAULT_WAIT_MILLIS = 15_000L
    }
}
