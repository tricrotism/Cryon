package com.tricrotism.cryon.common.deploy

/**
 * What a repository is advertising: every ref it has, and which one HEAD points at.
 *
 * One value rather than a call per question, because it all arrives in a single response. Resolving a
 * branch with a fallback has to know both what exists and what the repository calls its default.
 *
 * @param defaultRef the ref HEAD points at, from `symref=HEAD:refs/heads/<name>` in the first ref's
 *   capability list, which is the only place a repository states it. Null when the server did not
 *   send it, which is why the fallback chain does not stop there
 */
class Advertisement(
    private val commits: Map<String, String>,
    val defaultRef: String?,
) {

    val refs: Set<String>
        get() = commits.keys

    /**
     * @return the commit [ref] points at, or null when the repository does not have that ref
     */
    fun commitOf(ref: String): String? = commits[ref]
}
