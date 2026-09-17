@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategorySoloAmbient
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive

/**
 * The app's one audio session, and which player is using it.
 *
 * iOS gives an app that says nothing the solo-ambient category: silenced by the ring/silent switch
 * and stopping whatever else plays. What §6's viewer wants depends on the moment, so each player
 * [claim]s the session with what it is about to do:
 *
 * - [Mode.Muted] — a video autoplaying without sound. Ambient, so it mixes: swiping past videos
 *   never stops a person's music.
 * - [Mode.Audible] — sound a person asked for by unmuting ([ViewerSound]). Playback, which is
 *   heard even with the phone on silent, because unmuting *is* that override; it pauses other
 *   audio.
 * - [Mode.FollowSwitch] — a Live Photo held with nothing unmuted. Solo ambient: sound when the
 *   switch says ring, none when it says silent, and other audio pauses either way.
 *
 * **Only the latest claim may release.** A swipe from one video to the next builds the new player
 * before the old one leaves composition, so the old player's release arrives *after* the new
 * claim; letting it through would deactivate the session under a video that is playing. Releasing
 * deactivates with notify-others, which is what lets Music or a podcast resume by itself.
 */
internal object IosAudio {

    enum class Mode { Muted, Audible, FollowSwitch }

    private var owner: Any? = null

    fun claim(who: Any, mode: Mode) {
        owner = who
        val session = AVAudioSession.sharedInstance()
        val category = when (mode) {
            Mode.Muted -> AVAudioSessionCategoryAmbient
            Mode.Audible -> AVAudioSessionCategoryPlayback
            Mode.FollowSwitch -> AVAudioSessionCategorySoloAmbient
        }
        session.setCategory(category, error = null)
        session.setActive(true, error = null)
    }

    fun release(who: Any) {
        if (owner !== who) return
        owner = null
        AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
    }
}
