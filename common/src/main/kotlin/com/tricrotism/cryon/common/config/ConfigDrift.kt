package com.tricrotism.cryon.common.config

/**
 * Checks a shipped `config.yml` against the keys the code actually declares.
 *
 * The template and the [ConfigKey] declarations each hold every default, and nothing made them agree.
 * A key added in a release but not written into the template is invisible forever to anyone who
 * already has a config: behaviour stays right, because the read sites carry the defaults, but an
 * option nobody can see is an option nobody turns on. A default changed in code and not in the
 * template is worse, since the file then documents behaviour the server does not have.
 *
 * Generating the template from the keys would remove the second copy, but it cannot: the file also
 * carries structure that is not a declared key at all. `remote.repositories` is keyed by names the
 * operator invents, `remote.artifacts` is a list they curate, and both are read through
 * [Config.children]/[Config.maps]. A generator would silently drop them. So the two copies stay and
 * this makes them agree, which is the property that was actually wanted.
 *
 * Run it from a test, not at boot. A mismatch is a mistake in the repository, not in a deployment,
 * and by the time a server is starting it is too late for anyone who can fix it to notice.
 */
object ConfigDrift {

    /**
     * @param keys everything the platform declares, from [ConfigSchema.keys]
     * @param template the shipped file, already parsed
     * @param commented paths deliberately shipped commented out, from [ConfigSchema.commented]
     * @param exempt paths that are declared but intentionally absent, such as a legacy alias
     * @return one complaint per problem, empty when the two agree
     */
    fun check(
        keys: List<ConfigKey<*>>,
        template: Map<String, Any?>,
        commented: Set<String> = emptySet(),
        exempt: Set<String> = emptySet(),
    ): List<String> {
        val complaints = mutableListOf<String>()

        for (key in keys) {
            if (key.path in exempt) continue

            val present = lookup(template, key.path)

            if (key.path in commented) {
                if (present != Missing) {
                    complaints += "${key.path} is meant to ship commented out, but the template sets it to '$present'"
                }
                continue
            }

            if (present == Missing) {
                complaints += when {
                    key.required -> "${key.path} is required but the template does not mention it"
                    else -> "${key.path} is declared with default '${key.default}' but the template does not mention it"
                }
                continue
            }

            if (key.required) continue

            if (!same(present, key.default)) {
                complaints += "${key.path} defaults to '${key.default}' in code but '$present' in the template"
            }
        }

        return complaints
    }

    private object Missing {
        override fun toString() = "<missing>"
    }

    private fun lookup(template: Map<String, Any?>, path: String): Any? {
        var node: Any? = template

        for (name in path.split('.')) {
            val map = node as? Map<*, *> ?: return Missing
            if (!map.containsKey(name)) return Missing
            node = map[name]
        }

        return node
    }

    /**
     * YAML hands back the narrowest type that fits, so a `Long` key reads as an `Int` and comparing
     * by equality would report every one of them as drifted.
     */
    private fun same(fromTemplate: Any?, declared: Any?): Boolean = when {
        fromTemplate is Number && declared is Number -> fromTemplate.toDouble() == declared.toDouble()
        fromTemplate is List<*> && declared is List<*> ->
            fromTemplate.map { it?.toString() } == declared.map { it?.toString() }

        fromTemplate == null -> declared == null || declared == ""
        else -> fromTemplate.toString() == declared?.toString()
    }
}
