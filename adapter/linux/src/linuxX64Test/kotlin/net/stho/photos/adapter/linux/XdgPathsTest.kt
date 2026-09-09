package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertEquals

/** Where §4's on-device layout ends up, and what happens when nobody said. */
class XdgPathsTest {

    @Test
    fun theEnvironmentDecidesWhenItIsSet() {
        val paths = XdgPaths(
            environment = mapOf(
                "XDG_CACHE_HOME" to "/var/cache/mine",
                "XDG_CONFIG_HOME" to "/etc/mine",
                "HOME" to "/home/someone",
            )::get,
        )
        assertEquals("/var/cache/mine/photos-cli", paths.cacheRoot)
        assertEquals("/etc/mine/photos-cli", paths.configRoot)
    }

    @Test
    fun homeIsTheFallback() {
        val paths = XdgPaths(environment = mapOf("HOME" to "/home/someone")::get)
        assertEquals("/home/someone/.cache/photos-cli", paths.cacheRoot)
        assertEquals("/home/someone/.config/photos-cli", paths.configRoot)
    }

    /**
     * The specification says a relative value is to be ignored, and here it matters: resolving
     * the cache root against whatever directory a timer started in is how a second copy of a
     * 39-hour import appears.
     */
    @Test
    fun aRelativeValueIsTreatedAsUnset() {
        val paths = XdgPaths(
            environment = mapOf(
                "XDG_CACHE_HOME" to "cache",
                "XDG_CONFIG_HOME" to "",
                "HOME" to "/home/someone",
            )::get,
        )
        assertEquals("/home/someone/.cache/photos-cli", paths.cacheRoot)
        assertEquals("/home/someone/.config/photos-cli", paths.configRoot)
    }
}
