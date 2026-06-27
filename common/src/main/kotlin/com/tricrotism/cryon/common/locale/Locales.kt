package com.tricrotism.cryon.common.locale

/**
 * Static handle to the installed [LocaleStore], so the platform locale resolver
 * (`Player.resolvedLocale()`) can read overrides without a service lookup. The core always installs
 * one: a [PlayerLocaleStore] (persistent, cross-server) when SQL + Redis are configured, otherwise a
 * [MemoryLocaleStore] (session-only). Null only before the core has enabled.
 */
object Locales {

    @Volatile
    var store: LocaleStore? = null
        private set

    fun install(store: LocaleStore?) {
        this.store = store
    }
}
