package com.tricrotism.cryon.common.config

/**
 * A config value that is missing when it is required, or set to something its key refuses.
 */
class ConfigException(message: String) : RuntimeException(message)
