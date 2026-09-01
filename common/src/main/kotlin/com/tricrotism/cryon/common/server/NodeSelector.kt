package com.tricrotism.cryon.common.server

/**
 * Which node a request should land on, expressed as a predicate rather than a name.
 *
 * The caller almost never wants *a specific node*. It wants "one with room", "the one already
 * hosting this party", "an empty one". Saying that as a predicate lets [Provisioner] answer from the
 * live registry and, when nothing matches, decide whether creating one is appropriate.
 */
fun interface NodeSelector {

    fun matches(node: Node): Boolean

    companion object {

        /** Ready and not full, the default for putting a player somewhere sensible. */
        val Available: NodeSelector = NodeSelector {
            it.state == NodeState.READY && it.playerCount < it.maxPlayers
        }

        /** Ready and empty. For work that wants a node to itself. */
        val Empty: NodeSelector = NodeSelector {
            it.state == NodeState.READY && it.playerCount == 0
        }

        /** Ready and advertising [key] = [value] in its metadata. */
        fun tagged(key: String, value: String): NodeSelector = NodeSelector {
            it.state == NodeState.READY && it.metadata[key] == value
        }
    }
}
