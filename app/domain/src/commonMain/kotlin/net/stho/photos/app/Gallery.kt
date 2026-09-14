package net.stho.photos.app

import kotlin.time.Instant
import kotlinx.io.files.Path
import net.stho.photos.model.MediaType

/**
 * The photo library an upload reads from (§8).
 *
 * PhotoKit on the phone and a directory on the desktop, so the whole upload flow is developed and
 * tested on the harness and the phone's own half is this port and the uploader beside it. A port
 * for two of §7's reasons: it differs by platform, and a suite needs one it can declare.
 */
public interface Gallery {
    /** Asks for access when that has not been decided yet, and says what was granted. */
    public suspend fun requestAccess(): GalleryAccess

    /** The library's albums, in the order the platform lists them. */
    public suspend fun albums(): List<GalleryAlbum>

    /** An album's assets, or every asset in the library when [album] is null — the loose picker. */
    public suspend fun assets(album: GalleryAlbum?): List<GalleryAsset>

    /** One asset by id, or null once it has left the library — for an upload resumed after a kill. */
    public suspend fun asset(id: String): GalleryAsset?

    /** A small JPEG for the picker's grid. Null when the platform cannot draw one. */
    public suspend fun thumbnail(asset: GalleryAsset): ByteArray?

    /**
     * Writes the asset's bytes under [directory] and describes them.
     *
     * The version the library shows, not the camera's: an edited photo exports its edit, and a
     * RAW exports its rendered image (§8). On the phone this is also where an iCloud-only asset
     * is downloaded, which is why it suspends.
     */
    public suspend fun export(asset: GalleryAsset, directory: Path): ExportedAsset

    /** Removes these assets from the library. The platform asks first; false when refused. */
    public suspend fun delete(ids: List<String>): Boolean

    /** Opens the system settings where library access is granted. */
    public fun openSettings()
}

public enum class GalleryAccess {
    Full,

    /** "Selected photos": no albums to list and nothing may be deleted, so upload refuses it (§8). */
    Limited,
    Denied,
}

public data class GalleryAlbum(val id: String, val name: String, val count: Int)

public data class GalleryAsset(
    val id: String,
    /** The camera's name for it, whose stem the uploaded file keeps: `IMG_1234`. */
    val filename: String,
    val mediaType: MediaType,
)

/** One asset on disk, ready to become a row. */
public class ExportedAsset(
    public val mediaType: MediaType,
    /** The still, or the video — named with the extension its bytes really have. */
    public val file: Path,
    /** A Live Photo's MOV. Null for anything else. */
    public val pairedVideo: Path? = null,
    /**
     * From the library's own record rather than the file's EXIF. Provisional: the laptop
     * re-derives every row from EXIF when it encodes the album (§8).
     */
    public val takenAt: Instant? = null,
    public val latitude: Double? = null,
    public val longitude: Double? = null,
    public val width: Int? = null,
    public val height: Int? = null,
    /** §5's thumbnail: a 256px square centre crop, JPEG q75. */
    public val thumbnail: ByteArray,
)
