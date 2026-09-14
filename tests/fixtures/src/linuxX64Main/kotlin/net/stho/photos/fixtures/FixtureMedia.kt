package net.stho.photos.fixtures

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The media set the app suites sync, written once per build.
 *
 * `:tests:app` runs on the JVM and `:tests:ios` on a Mac, and neither can reach the C shim that
 * encodes these — so this executable writes them to a directory, the desktop suite reads that
 * directory by path, and CI hands the same directory to the macOS job as an artifact. One
 * generator is what makes the two suites assert against byte-identical media.
 *
 * Every file has its own dimensions on purpose. §2 names a blob by its content, so two files
 * with equal bytes would be one blob, and a scenario asserting "exactly this album's objects"
 * would be asserting against a set smaller than the rows it declared.
 *
 * The Live Photo pair is one iOS assembles: the `.mov` is QuickTime with its `mdta` metadata box
 * directly under `moov`, and the still carries Apple's maker note big-endian, as an iPhone writes
 * both. The iOS suite asserts that `PHLivePhoto` builds a full Live Photo from exactly these files.
 */
public fun main(args: Array<String>) {
    val out = Path(requireNotNull(args.firstOrNull()) { "usage: fixtureMedia <directory>" })
    SystemFileSystem.createDirectories(out)

    writeSyntheticHeic(Path(out, FixtureMedia.PHOTO), 320, 240)
    writeSyntheticHeic(Path(out, FixtureMedia.POSTER), 336, 252)
    writeSyntheticVideo(Path(out, FixtureMedia.VIDEO), width = 64, height = 48, frames = 10)
    writeSyntheticHeic(Path(out, FixtureMedia.LIVE_VIEW), 352, 264)
    writeSyntheticHeic(
        Path(out, FixtureMedia.LIVE_STILL), 368, 276,
        contentIdentifier = FixtureMedia.LIVE_IDENTIFIER,
    )
    writeSyntheticVideo(
        Path(out, FixtureMedia.LIVE_VIDEO), width = 80, height = 60, frames = 10,
        contentIdentifier = FixtureMedia.LIVE_IDENTIFIER,
    )
    println("fixture media in $out")
}

/**
 * The file names both suites read.
 *
 * Spelled again in each suite's zone builder, which cannot link this linuxX64 module — so a rename
 * here is a rename there, and the suite fails loudly on the missing file rather than quietly.
 */
public object FixtureMedia {
    /** A still's 3200px-tier viewing image — small here, since only its bytes are asserted. */
    public const val PHOTO: String = "photo.heic"

    /** A video row's `image_id`: the poster the viewer shows until the transcode lands. */
    public const val POSTER: String = "poster.heic"

    /** A video row's `video_id`: §5's HEVC/MP4 transcode. */
    public const val VIDEO: String = "video.mp4"

    /** A Live Photo row's `image_id`: the ordinary viewing image. */
    public const val LIVE_VIEW: String = "live-view.heic"

    /** A Live Photo row's `live_still_id`: the untouched still, carrying the pairing identifier. */
    public const val LIVE_STILL: String = "live-still.heic"

    /** A Live Photo row's `live_video_id`: the MOV carrying the same identifier. */
    public const val LIVE_VIDEO: String = "live.mov"

    public const val LIVE_IDENTIFIER: String = "5E1C9A2B-7D40-4F3E-9B61-2A8C0D4E7F10"
}
