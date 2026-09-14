@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package net.stho.photos.ios

import kotlin.coroutines.resume
import kotlin.time.Instant
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import net.stho.photos.app.ExportedAsset
import net.stho.photos.app.Gallery
import net.stho.photos.app.GalleryAccess
import net.stho.photos.app.GalleryAlbum
import net.stho.photos.app.GalleryAsset
import net.stho.photos.model.MediaType
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAny
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetMediaSubtypePhotoLive
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceType
import platform.Photos.PHAssetResourceTypeFullSizePairedVideo
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.Photos.PHImageContentMode
import platform.Photos.PHImageContentModeAspectFill
import platform.Photos.PHImageContentModeDefault
import platform.Photos.PHImageManager
import platform.Photos.PHImageManagerMaximumSize
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsVersionCurrent
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/**
 * §8's gallery on the phone: PhotoKit, with full library access.
 *
 * A custom browser over `PHAssetCollection` rather than `PHPickerViewController`, because the
 * picker can neither list nor name albums (so no name to prefill) nor delete (so no clean-up
 * after upload). Those two requirements are what force the real permission grant.
 *
 * **What is exported is what Photos shows.** A still comes through `PHImageManager` at the
 * current version — its edit when there is one, and a RAW+JPEG pair's JPEG — and a format the
 * pull cannot use, such as ProRAW's DNG, is rendered instead. Video and a Live Photo's MOV come
 * through `PHAssetResourceManager`, the edited full-size resource first. Network access is
 * allowed throughout, which is how an iCloud-only asset reaches the disk during preparation.
 *
 * Unverified, and deferred to a device: whether an *edited* Live Photo's still and MOV still carry
 * the content identifier that pairs them (§8).
 */
internal class PhotoKitGallery : Gallery {

    override suspend fun requestAccess(): GalleryAccess = suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { status ->
            continuation.resume(
                when (status) {
                    PHAuthorizationStatusAuthorized -> GalleryAccess.Full
                    PHAuthorizationStatusLimited -> GalleryAccess.Limited
                    else -> GalleryAccess.Denied
                },
            )
        }
    }

    override suspend fun albums(): List<GalleryAlbum> {
        val collections = PHAssetCollection.fetchAssetCollectionsWithType(PHAssetCollectionTypeAlbum, PHAssetCollectionSubtypeAny, null)
        return collections.items<PHAssetCollection>().map { collection ->
            GalleryAlbum(
                id = collection.localIdentifier,
                name = collection.localizedTitle ?: "Untitled",
                count = PHAsset.fetchAssetsInAssetCollection(collection, null).count.toInt(),
            )
        }
    }

    /**
     * Oldest first, like an album here (§3). Listed without filenames: reading an asset's
     * resources is a query per asset, and the picker never shows a name — [asset] fills it in.
     */
    override suspend fun assets(album: GalleryAlbum?): List<GalleryAsset> {
        val options = PHFetchOptions().apply {
            sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = true))
        }
        val result = if (album == null) {
            PHAsset.fetchAssetsWithOptions(options)
        } else {
            val collection = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(listOf(album.id), null)
                .firstObject as? PHAssetCollection ?: return emptyList()
            PHAsset.fetchAssetsInAssetCollection(collection, options)
        }
        return result.items<PHAsset>().map { it.describe(filename = "") }
    }

    override suspend fun asset(id: String): GalleryAsset? {
        val asset = find(id) ?: return null
        val original = resources(asset)
            .firstOrNull { it.type == PHAssetResourceTypePhoto || it.type == PHAssetResourceTypeVideo }
            ?.originalFilename
        return asset.describe(filename = original ?: "IMG")
    }

    override suspend fun thumbnail(asset: GalleryAsset): ByteArray? =
        find(asset.id)?.let { image(it, CGSizeMake(2.0 * SIDE, 2.0 * SIDE), PHImageContentModeAspectFill) }?.squareJpeg()

    override suspend fun export(asset: GalleryAsset, directory: Path): ExportedAsset {
        val found = requireNotNull(find(asset.id)) { "the photo left the library before it could be exported" }
        NSFileManager.defaultManager.createDirectoryAtPath(directory.toString(), withIntermediateDirectories = true, attributes = null, error = null)
        val resources = resources(found)
        val file: Path
        var paired: Path? = null
        when (asset.mediaType) {
            MediaType.VIDEO -> file = write(resources.pick(PHAssetResourceTypeFullSizeVideo, PHAssetResourceTypeVideo), directory)
            MediaType.PHOTO -> file = still(found, directory)
            MediaType.LIVE_PHOTO -> {
                file = still(found, directory)
                paired = write(resources.pick(PHAssetResourceTypeFullSizePairedVideo, PHAssetResourceTypePairedVideo), directory)
            }
        }
        val thumbnail = image(found, CGSizeMake(2.0 * SIDE, 2.0 * SIDE), PHImageContentModeAspectFill)?.squareJpeg()
        val coordinate = found.location?.coordinate?.useContents { latitude to longitude }
        return ExportedAsset(
            mediaType = asset.mediaType,
            file = file,
            pairedVideo = paired,
            takenAt = found.creationDate?.let { Instant.fromEpochMilliseconds((it.timeIntervalSince1970 * 1000).toLong()) },
            latitude = coordinate?.first,
            longitude = coordinate?.second,
            width = found.pixelWidth.toInt(),
            height = found.pixelHeight.toInt(),
            thumbnail = requireNotNull(thumbnail) { "the photo library drew no thumbnail" },
        )
    }

    /** iOS shows its own confirmation; an app cannot delete library assets silently (§8). */
    override suspend fun delete(ids: List<String>): Boolean = suspendCancellableCoroutine { continuation ->
        val assets = PHAsset.fetchAssetsWithLocalIdentifiers(ids, null)
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            { PHAssetChangeRequest.deleteAssets(assets) },
        ) { success, _ -> if (continuation.isActive) continuation.resume(success) }
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    // ------------------------------------------------------------------------------ exporting

    private suspend fun still(asset: PHAsset, directory: Path): Path {
        val (data, uti) = suspendCancellableCoroutine<Pair<NSData?, String?>> { continuation ->
            val options = PHImageRequestOptions().apply {
                version = PHImageRequestOptionsVersionCurrent
                deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
                networkAccessAllowed = true
            }
            PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(asset, options) { data, uti, _, _ ->
                if (continuation.isActive) continuation.resume(data to uti)
            }
        }
        val extension = when (uti) {
            "public.heic" -> "heic"
            "public.jpeg" -> "jpg"
            "public.png" -> "png"
            else -> null
        }
        if (data != null && extension != null) return save(data, directory, extension)
        // A RAW, or anything the pull cannot use: rendered, exactly as Photos draws it.
        val rendered = requireNotNull(image(asset, PHImageManagerMaximumSize, PHImageContentModeDefault)) {
            "the photo library could not render this photo"
        }
        return save(requireNotNull(UIImageJPEGRepresentation(rendered, 0.92)), directory, "jpg")
    }

    private suspend fun write(resource: PHAssetResource, directory: Path): Path {
        val target = Path(directory, "${NSUUID().UUIDString}.${resource.originalFilename.substringAfterLast('.', "mov")}")
        val failure = suspendCancellableCoroutine<NSError?> { continuation ->
            val options = PHAssetResourceRequestOptions().apply { networkAccessAllowed = true }
            PHAssetResourceManager.defaultManager().writeDataForAssetResource(
                resource, NSURL.fileURLWithPath(target.toString()), options,
            ) { error -> if (continuation.isActive) continuation.resume(error) }
        }
        check(failure == null) { "the photo library could not export ${resource.originalFilename}: ${failure?.localizedDescription}" }
        return target
    }

    private fun save(data: NSData, directory: Path, extension: String): Path {
        val target = Path(directory, "${NSUUID().UUIDString}.$extension")
        check(data.writeToFile(target.toString(), atomically = true)) { "could not write $target" }
        return target
    }

    private suspend fun image(asset: PHAsset, size: CValue<CGSize>, mode: PHImageContentMode): UIImage? =
        suspendCancellableCoroutine { continuation ->
            val options = PHImageRequestOptions().apply {
                deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
                networkAccessAllowed = true
            }
            PHImageManager.defaultManager().requestImageForAsset(asset, size, mode, options) { image, _ ->
                if (continuation.isActive) continuation.resume(image)
            }
        }

    /** §5's thumbnail: a 256px square centre crop, JPEG q75. `drawInRect` applies the orientation. */
    private fun UIImage.squareJpeg(): ByteArray? {
        val (width, height) = size.useContents { width to height }
        if (width <= 0.0 || height <= 0.0) return null
        val scale = SIDE / minOf(width, height)
        val format = UIGraphicsImageRendererFormat().apply { this.scale = 1.0 }
        val square = UIGraphicsImageRenderer(size = CGSizeMake(SIDE, SIDE), format = format).imageWithActions { _ ->
            drawInRect(CGRectMake((SIDE - width * scale) / 2, (SIDE - height * scale) / 2, width * scale, height * scale))
        }
        return UIImageJPEGRepresentation(square, 0.75)?.toByteArray()
    }

    // -------------------------------------------------------------------------------- lookups

    private fun find(id: String): PHAsset? =
        PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null).firstObject as? PHAsset

    private fun resources(asset: PHAsset): List<PHAssetResource> =
        PHAssetResource.assetResourcesForAsset(asset).filterIsInstance<PHAssetResource>()

    private fun List<PHAssetResource>.pick(preferred: PHAssetResourceType, fallback: PHAssetResourceType): PHAssetResource =
        firstOrNull { it.type == preferred } ?: first { it.type == fallback }

    private fun PHAsset.describe(filename: String): GalleryAsset = GalleryAsset(
        id = localIdentifier,
        filename = filename,
        mediaType = when {
            mediaType == PHAssetMediaTypeVideo -> MediaType.VIDEO
            (mediaSubtypes and PHAssetMediaSubtypePhotoLive) != 0uL -> MediaType.LIVE_PHOTO
            else -> MediaType.PHOTO
        },
    )

    private inline fun <reified T> PHFetchResult.items(): List<T> =
        (0 until count.toInt()).mapNotNull { objectAtIndex(it.toULong()) as? T }

    private fun NSData.toByteArray(): ByteArray {
        val out = ByteArray(length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }

    private companion object {
        const val SIDE = 256.0
    }
}
