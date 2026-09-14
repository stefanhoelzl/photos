package net.stho.photos.iostest

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §1 on the phone, through the real Keychain (decisions 9 and 16): set up once, stay set up across
 * a relaunch, log out, and stay logged out across a relaunch.
 *
 * iOS-only. The desktop's setup path is covered by the `Account`/`Launcher` unit tests; what only
 * this suite can show is that the Keychain adapter keeps and forgets what it is given.
 */
class SetupTest {

    @Test
    fun setUpSurvivesARelaunchAndLoggingOutDoesToo() = iosScenario("setup") {
        zone.album("Iceland", photos = 1)
        install()

        assertEquals("setup", launch().string("screen"), "a fresh install asks for credentials")
        screenshot("setup-fresh")
        assertEquals("albums", setUp().string("screen"))

        assertEquals("albums", launch().string("screen"), "relaunched, the Keychain still has them")
        settle()

        assertEquals("setup", post("/logout").string("screen"))
        assertEquals("setup", launch().string("screen"), "relaunched after log-out, they are gone")
        screenshot("setup-logged-out")
    }
}
