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
) {
    val app1 = exifApp1(orientation, model)
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

/** A short HEVC/MP4 clip, optionally carrying a display matrix and a Live Photo identifier. */
public fun writeSyntheticVideo(
    path: Path,
    width: Int = 64,
    height: Int = 48,
    frames: Int = 10,
    rotation: Int = 0,
    contentIdentifier: String? = null,
) {
    imagingCall { err ->
        pi_fixture_write_video(path.toString(), width, height, frames, rotation, contentIdentifier, err)
    }
}
