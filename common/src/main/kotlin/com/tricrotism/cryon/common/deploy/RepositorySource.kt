package com.tricrotism.cryon.common.deploy

import java.nio.file.Path

/**
 * Where a deploy tree comes from.
 *
 * An interface with one production implementation, because the alternative is a delivery feature
 * nobody can exercise without a forge and a network. [GitDeploy]'s semantics are the part worth
 * proving, and none of them are about HTTP.
 */
interface RepositorySource {

    /**
     * @return every ref the repository has, and its default branch
     */
    fun advertise(): Advertisement

    /**
     * Lay the tree at [commit] out under [workDirectory].
     *
     * @return where it landed
     */
    fun fetch(commit: String, workDirectory: Path): Path
}
