package com.tricrotism.cryon.common.currency

/**
 * How far a currency's balances reach.
 *
 * The distinction is the same one [com.tricrotism.cryon.common.flag.FeatureFlags] draws between its
 * server and global scopes, and it is a property of the *currency*, not of the deployment: a shop
 * token that only means something on one gamemode is [SERVER] even on a single-server network, and a
 * network-wide premium currency is [GLOBAL] even when only one instance is running.
 */
enum class CurrencyScope {

    /**
     * One balance per serverId. `survival` and `skyblock` keep separate books, and every instance of a
     * serverId shares one, which is the only sane reading of a pooled serverId, since a player may land
     * on any instance of it.
     */
    SERVER,

    /**
     * One balance across the whole network, wherever the player is.
     */
    GLOBAL,
}
