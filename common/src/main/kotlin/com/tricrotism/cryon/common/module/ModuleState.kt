package com.tricrotism.cryon.common.module

/** Lifecycle state of a [Module], surfaced by the module-manager command. */
enum class ModuleState {
    /** Registered from a jar but not yet loaded. */
    REGISTERED,

    /** `onLoad` ran (services published); awaiting enable. */
    LOADED,

    /** `onEnable` ran; the feature is live. */
    ENABLED,

    /** Disabled at runtime; can be re-enabled. */
    DISABLED,

    /** `onLoad`/`onEnable` threw — left out of the live set. */
    FAILED,
}
