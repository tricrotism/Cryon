package com.tricrotism.cryon.common.locale

/**
 * Static handle to the installed [PlayerLocaleStore], so the platform locale resolver
 * (`Player.resolvedLocale()`) can read overrides without a service lookup. Installed by the core
 * when SQL + Redis are both configured; null otherwise (resolution falls back to the client locale).
 */
object Locales {

    @Volatile
    var store: PlayerLocaleStore? = null
        private set

    fun install(store: PlayerLocaleStore?) {
        this.store = store
    }
}
