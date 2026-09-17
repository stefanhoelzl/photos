package net.stho.photos.app

import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album

/**
 * An album the upload dialog can put photos into (§8): one the catalog shows, one this phone is
 * still uploading, or one the dialog has just added and nothing has created yet.
 *
 * Every upload is an addition to [id]. For an album that exists that id is the album's; for a new
 * one it is minted when the entry is added, and every later upload into it reuses it.
 */
public data class UploadTarget(
    val id: Uuid,
    val name: String,
    val parent: Uuid?,
    /** Its containers and its name, `Trips / Italy`: the label, and what typing matches. */
    val path: String,
    val kind: Kind,
) {
    public enum class Kind {
        /** In the catalog. */
        Album,

        /** A new album this phone is still uploading, which no catalog shows yet. */
        Uploading,

        /** Added in the dialog, and created by nothing until its upload starts. */
        New,
    }
}

/** What the album field's text names. */
public sealed interface Resolution {
    /** Nothing to name yet: an empty field, or a path still ending in `/`. */
    public data object Incomplete : Resolution

    /** An entry, matched exactly — its path ignoring case and the spaces around each `/`. */
    public data class Entry(val target: UploadTarget) : Resolution

    /** No album has this path, so it can be added as a new one. */
    public data class New(val name: String, val parent: Uuid?, val parentPath: String, val path: String) : Resolution

    /** A path the phone cannot make an album at, and why, in the words the dialog shows. */
    public data class Refused(val reason: String) : Resolution
}

/**
 * The album tree as the upload dialog reads it: which albums hold photos and which hold albums
 * (§2's XOR), with each one's path from the library root.
 */
public class UploadTargets(
    /** Every album in the catalog, parents before children, in the order the list shows them. */
    albums: List<Album>,
    /** The ones with sub-albums. An album with neither photos nor sub-albums can take photos. */
    containers: Set<Uuid>,
    /** New albums this phone is still uploading. */
    uploading: List<UploadTarget> = emptyList(),
) {
    private val byId = albums.associateBy(Album::id)
    private val containers = containers.toSet()
    private val children = albums.groupBy(Album::parent)

    /** Every album that can take photos, then this phone's albums still uploading, labelled by path. */
    public val entries: List<UploadTarget> =
        albums.filter { it.id !in this.containers }.map { UploadTarget(it.id, it.name, it.parent, pathOf(it.id), UploadTarget.Kind.Album) } +
            uploading.filter { it.id !in byId }.distinctBy(UploadTarget::id)

    /** The path of a catalog album, `Trips / Italy`; the empty string for the root. */
    public fun pathOf(id: Uuid?): String {
        val names = generateSequence(id?.let(byId::get)) { album -> album.parent?.let(byId::get) }
            .map(Album::name)
            .toList()
        return names.reversed().joinToString(PATH_SEPARATOR)
    }

    /**
     * What [text] names, with [added] — the entries added in the dialog — offered as well.
     *
     * A path is absolute: its last segment is the album, every one before it a container, matched
     * ignoring case. Names are unique among siblings (§2), so no segment is ever ambiguous.
     */
    public fun resolve(text: String, added: List<UploadTarget> = emptyList()): Resolution {
        val segments = text.split('/').map(String::trim)
        if (segments.all(String::isEmpty)) return Resolution.Incomplete
        val key = segments.joinToString(PATH_SEPARATOR).lowercase()
        (added + entries).firstOrNull { it.path.lowercase() == key }?.let { return Resolution.Entry(it) }

        var parent: Album? = null
        for (segment in segments.dropLast(1)) {
            if (segment.isEmpty()) return Resolution.Incomplete
            val found = children[parent?.id].orEmpty().firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return Resolution.Refused("No container \"$segment\"")
            if (found.id !in containers) return Resolution.Refused("\"${found.name}\" holds photos, not albums")
            parent = found
        }
        val name = segments.last()
        if (name.isEmpty()) return Resolution.Incomplete
        val sibling = children[parent?.id].orEmpty().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (sibling != null && sibling.id in containers) return Resolution.Refused("\"${sibling.name}\" holds albums, not photos")
        val parentPath = pathOf(parent?.id)
        return Resolution.New(
            name = name,
            parent = parent?.id,
            parentPath = parentPath,
            path = if (parentPath.isEmpty()) name else parentPath + PATH_SEPARATOR + name,
        )
    }

    /**
     * The field's text when the dialog opens: the album the upload started in; else the container it
     * started in, followed by the gallery album's name — nothing more for loose photos.
     */
    public fun prefill(start: Uuid?, addTo: Uuid?, galleryName: String?): String {
        if (addTo != null) entries.firstOrNull { it.id == addTo }?.let { return it.path }
        val container = pathOf(start)
        val name = galleryName.orEmpty()
        return if (container.isEmpty()) name else container + PATH_SEPARATOR + name
    }

    public companion object {
        public const val PATH_SEPARATOR: String = " / "
    }
}
