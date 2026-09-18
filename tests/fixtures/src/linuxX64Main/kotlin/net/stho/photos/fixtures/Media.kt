@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.fixtures

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import photosimaging.pi_fixture_write_heic_with_exif
import photosimaging.pi_fixture_write_video

/**
 * A JPEG carrying an EXIF orientation tag.
 *
 * Worth generating because its absence hid a real bug: libjpeg does not rotate, so without an
 * oriented fixture nothing proved the pipeline was baking orientation at all.
 */
public fun syntheticOrientedJpeg(
    width: Int,
    height: Int,
    orientation: Int,
    quality: Int = 90,
): ByteArray = syntheticJpeg(width, height, quality).withApp1(exifApp1(orientation = orientation))

/**
 * A HEIC that actually carries EXIF.
 *
 * [syntheticHeic] encodes pixels and nothing else, so for a long time *every* synthetic HEIC
 * had no metadata -- and a HEIF EXIF reader that returned nothing at all passed the entire suite
 * while dropping the date, GPS and Live Photo identifier of all 1,531 HEICs in the real library.
 */
public fun writeSyntheticHeic(
    path: Path,
    width: Int,
    height: Int,
    orientation: Int? = null,
    model: String? = null,
    contentIdentifier: String? = null,
) {
    val app1 = exifApp1(orientation, model, contentIdentifier)
    imagingCall { err ->
        app1.usePinned { pinned ->
            pi_fixture_write_heic_with_exif(
                path.toString(),
                width,
                height,
                pinned.addressOf(0).reinterpret<UByteVar>(),
                app1.size.convert(),
                err,
            )
        }
    }
}

/**
 * A short HEVC clip, optionally carrying a display matrix, a Live Photo identifier and a tone.
 *
 * A `.mov` is QuickTime and anything else MP4 (the shim picks the muxer). A `.mov` carrying an
 * identifier then has its metadata moved to where Apple writes it — see [toQuickTimeMetadata].
 *
 * [audioSampleRate] leaves the tone as a phone writes it — AAC at 44100 Hz — when it is 0, and
 * otherwise writes it as PCM at that rate, the way the library's compact cameras did. 7875 and
 * 11024 Hz are real rates off real cameras and are rates AAC cannot be encoded at, so a fixture
 * asking for one is asking the transcoder to resample rather than fail.
 */
public fun writeSyntheticVideo(
    path: Path,
    width: Int = 64,
    height: Int = 48,
    frames: Int = 10,
    rotation: Int = 0,
    contentIdentifier: String? = null,
    audio: Boolean = false,
    audioSampleRate: Int = 0,
) {
    imagingCall { err ->
        pi_fixture_write_video(
            path.toString(), width, height, frames, rotation, contentIdentifier,
            if (audio) 1 else 0, audioSampleRate, err,
        )
    }
    if (contentIdentifier != null && path.name.endsWith(".mov", ignoreCase = true)) {
        path.write(toQuickTimeMetadata(path.readBytes()))
    }
}

/**
 * ffmpeg's metadata, reframed the way `PHLivePhoto` requires it.
 *
 * ffmpeg's mov muxer writes the right *contents* — an `mdta` handler, a `keys` box naming
 * `com.apple.quicktime.content.identifier`, the value as item 1 — but as an ISO full box inside
 * `moov/udta`, where AVFoundation reads iTunes tags. Apple writes the same `meta` directly under
 * `moov`, with no version and flags. Measured on macOS against this very file: as ffmpeg wrote it,
 * and with the box only moved, `PHLivePhoto` assembles nothing; moved *and* reframed, it assembles a
 * full Live Photo, and AVFoundation lists the identifier under `com.apple.quicktime.mdta`.
 *
 * `moov` follows `mdat` here, so changing its size moves no chunk offset. That is required, and
 * checked, rather than assumed.
 */
internal fun toQuickTimeMetadata(file: ByteArray): ByteArray {
    val top = boxes(file, 0, file.size)
    val mdat = top.single { it.type == "mdat" }
    val moov = top.single { it.type == "moov" }
    check(moov.at > mdat.at) { "moov precedes mdat; resizing it would move every chunk offset" }
    val udta = boxes(file, moov.at + 8, moov.end).single { it.type == "udta" }
    val meta = boxes(file, udta.at + 8, udta.end).single { it.type == "meta" }

    // QuickTime `meta`: the same children, without the 4 bytes of version and flags.
    val children = file.copyOfRange(meta.at + 12, meta.end)
    val quickTime = be32(8 + children.size) + "meta".encodeToByteArray() + children

    val otherMoovChildren = boxes(file, moov.at + 8, moov.end).filter { it.type != "udta" }
    val remainingUdta = boxes(file, udta.at + 8, udta.end).filter { it.type != "meta" }
    val newUdta = if (remainingUdta.isEmpty()) ByteArray(0) else {
        val body = remainingUdta.fold(ByteArray(0)) { acc, box -> acc + file.copyOfRange(box.at, box.end) }
        be32(8 + body.size) + "udta".encodeToByteArray() + body
    }
    val moovBody = otherMoovChildren.fold(ByteArray(0)) { acc, box -> acc + file.copyOfRange(box.at, box.end) } +
        newUdta + quickTime
    val newMoov = be32(8 + moovBody.size) + "moov".encodeToByteArray() + moovBody
    return file.copyOfRange(0, moov.at) + newMoov + file.copyOfRange(moov.end, file.size)
}

private class Box(val type: String, val at: Int, val end: Int)

/** The boxes between [start] and [end], 32-bit sizes only — all a fixture writer produces. */
private fun boxes(file: ByteArray, start: Int, end: Int): List<Box> = buildList {
    var at = start
    while (at + 8 <= end) {
        val size = ((file[at].toInt() and 0xFF) shl 24) or ((file[at + 1].toInt() and 0xFF) shl 16) or
            ((file[at + 2].toInt() and 0xFF) shl 8) or (file[at + 3].toInt() and 0xFF)
        check(size >= 8 && at + size <= end) { "malformed box at $at" }
        add(Box(file.copyOfRange(at + 4, at + 8).decodeToString(), at, at + size))
        at += size
    }
}
