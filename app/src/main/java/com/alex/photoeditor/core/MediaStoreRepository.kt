package com.alex.photoeditor.core

import android.content.ContentValues
import android.content.Context
import androidx.core.content.FileProvider
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.exifinterface.media.ExifInterface
import java.util.Locale

/** The file families the gallery can browse. */
enum class MediaKind { IMAGE, VIDEO, AUDIO, DOCUMENT, APK }

/** One media file visible through MediaStore. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val kind: MediaKind,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Seconds since epoch, from MediaStore. */
    val dateAdded: Long,
    val dateModified: Long,
    /** Milliseconds; null for images. */
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    /** Path of the item relative to its storage volume root, e.g. "DCIM/Camera/". */
    val relativePath: String?,
    /** Storage volume name, e.g. "external_primary" (internal) or "1A2B-3C4D" (SD card). */
    val volumeName: String?,
    /** Display name of the containing folder, when MediaStore reports one. */
    val bucket: String?,
    /**
     * Strict bucket identity: a stable hash of the absolute folder path.
     *
     * Two folders that share a display name (e.g. two "Camera" folders on
     * different volumes) must never be merged, and the grouping is therefore
     * done on this id rather than on the name. Null for items outside any
     * folder, which the gallery groups under the phone's model name.
     */
    val bucketId: Long?,
    /** Audio only: track title, artist and album when the tag is present. */
    val title: String?,
    val artist: String?,
    val album: String?,
    /**
     * Set for files found directly on disk (documents/APKs) so every file
     * operation can use the real path instead of a MediaStore DATA lookup.
     */
    val absolutePath: String? = null
) {
    /** Human-readable name: the tag title for audio, the file name otherwise. */
    val displayName: String
        get() = title?.takeIf { it.isNotBlank() } ?: name
}

/**
 * Reads the device's media indexes.
 *
 * Queries run off the main thread and cap the result, newest first: a phone
 * with tens of thousands of photos would otherwise build a list the grid
 * never notices, and date order is what people expect a gallery to open
 * with.
 */
object MediaStoreRepository {

    private const val MAX_ITEMS = 800

    /**
     * Documents and APKs are produced by one combined pass, shared by both
     * tab queries launched during a refresh. Without this, every refresh
     * scanned the same storage tree twice.
     */
    private data class FileScan(val at: Long, val docs: List<MediaItem>, val apks: List<MediaItem>)
    private var fileScan: FileScan? = null
    private val fileScanLock = Mutex()

    /**
     * Directory entries cache, keyed by path + directory mtime. Unchanged
     * folders are never listed again; adding/removing/renaming a file changes
     * mtime and naturally invalidates the entry.
     */
    private data class DirListing(val stamp: Long, val files: Array<java.io.File>)
    private val dirCache = HashMap<String, DirListing>()

    fun invalidateFileCache() {
        fileScan = null
    }

    private suspend fun fileLists(context: Context): Pair<List<MediaItem>, List<MediaItem>> =
        fileScanLock.withLock {
            val cached = fileScan
            if (cached != null && System.currentTimeMillis() - cached.at < 2_000L) {
                return@withLock cached.docs to cached.apks
            }
            val lists = coroutineScope {
                val disk = async(Dispatchers.IO) { queryFilesFromDisk(context) }
                val provider = async(Dispatchers.IO) { queryFiles(context) }
                listOf(disk.await(), provider.await())
            }
            fun merge(a: List<MediaItem>, b: List<MediaItem>): List<MediaItem> {
                val seen = HashSet<String>()
                return (a + b).filter { item ->
                    val key = item.absolutePath ?: item.uri.toString()
                    seen.add(key)
                }
            }
            val combined = merge(lists[0], lists[1])
            val result = FileScan(
                System.currentTimeMillis(),
                combined.filter { it.kind == MediaKind.DOCUMENT }
                    .sortedByDescending { it.dateAdded },
                combined.filter { it.kind == MediaKind.APK }
                    .sortedByDescending { it.dateAdded }
            )
            fileScan = result
            result.docs to result.apks
        }

    /** SVGs rasterize into this maximum edge so they never eat the heap. */
    private const val SVG_MAX_EDGE = 2048

    // A 1x1 transparent PNG, created and deleted again by createPublicFolder.
    private const val MARKER_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    suspend fun query(context: Context, kind: MediaKind): List<MediaItem> =
        withContext(Dispatchers.IO) {
            when (kind) {
                MediaKind.IMAGE -> queryCollection(context, kind)
                MediaKind.VIDEO -> queryCollection(context, kind)
                MediaKind.AUDIO -> queryCollection(context, kind)
                MediaKind.DOCUMENT, MediaKind.APK -> {
                    // One combined scan serves both tabs; the result is reused
                    // by the sibling tab query in the same refresh.
                    val (docs, apks) = fileLists(context)
                    if (kind == MediaKind.DOCUMENT) docs else apks
                }
            }
        }

    /**
     * Walks shared storage with the all-files access permission the app
     * requires at launch, so documents and APKs appear with their real
     * folders even when MediaStore doesn't index non-media files.
     */
    private fun queryFilesFromDisk(context: Context): List<MediaItem> {
        val extensions = DOCUMENT_EXTENSIONS.toSet() + "apk"
        val roots = mutableListOf<java.io.File>(FolderMaker.sharedStorageRoot())
        context.getExternalFilesDirs(null).drop(1).forEach { appDir ->
            appDir?.parentFile?.parentFile?.parentFile?.let(roots::add)
        }

        val result = ArrayList<MediaItem>()
        val seen = HashSet<String>()
        // MaterialFiles lists one directory at a time — never the whole tree.
        // Mirror that: bounded depth + a wall-clock budget, so a huge storage
        // can never leave the gallery stuck on "loading".
        val deadline = System.currentTimeMillis() + 1200L
        roots.forEach { root ->
            fun walk(dir: java.io.File, depth: Int) {
                if (depth > 3 || result.size >= MAX_FILES_ITEMS ||
                    System.currentTimeMillis() > deadline
                ) return
                val stamp = runCatching { dir.lastModified() }.getOrDefault(0L)
                val cached = synchronized(dirCache) { dirCache[dir.absolutePath] }
                val children = if (cached != null && cached.stamp == stamp) {
                    cached.files
                } else {
                    val fresh = runCatching { dir.listFiles() }.getOrNull() ?: return
                    synchronized(dirCache) { dirCache[dir.absolutePath] = DirListing(stamp, fresh) }
                    fresh
                }
                children.forEach { file ->
                    when {
                        file.isDirectory -> {
                            val name = file.name
                            // Android/data and hidden dirs are off-limits (or
                            // trash) — ordinary document folders are not.
                            if (name == "Android" || name.startsWith(".") ||
                                name.equals("cache", ignoreCase = true)
                            ) return@forEach
                            walk(file, depth + 1)
                        }
                        file.isFile -> {
                            val ext = file.name.substringAfterLast('.', "").lowercase(Locale.US)
                            if (ext !in extensions) return@forEach
                            val itemKind = if (ext == "apk") MediaKind.APK else MediaKind.DOCUMENT
                            val path = file.absolutePath
                            if (!seen.add(path)) return@forEach
                            val parent = file.parentFile ?: return@forEach
                            val relativeParent = runCatching {
                                parent.absolutePath.removePrefix(root.absolutePath).trimStart('/')
                            }.getOrDefault("")
                            val uri = runCatching {
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                            }.getOrNull() ?: return@forEach
                            result.add(
                                MediaItem(
                                    id = path.hashCode().toLong(),
                                    uri = uri,
                                    kind = itemKind,
                                    name = file.name,
                                    mimeType = if (itemKind == MediaKind.APK) {
                                        "application/vnd.android.package-archive"
                                    } else {
                                        SystemTrash.mimeTypeForName(file.name)
                                    },
                                    sizeBytes = file.length(),
                                    dateAdded = file.lastModified() / 1000,
                                    dateModified = file.lastModified() / 1000,
                                    durationMs = null,
                                    width = null,
                                    height = null,
                                    relativePath = if (relativeParent.isBlank()) null else "$relativeParent/",
                                    volumeName = "external",
                                    bucket = parent.name,
                                    bucketId = parent.absolutePath.hashCode().toLong(),
                                    title = null,
                                    artist = null,
                                    album = null,
                                    absolutePath = path
                                )
                            )
                        }
                    }
                }
            }
            walk(root, 0)
        }
        return result
    }

    /** Extensions grouped as "documents" in the gallery. */
    private val DOCUMENT_EXTENSIONS = listOf(
        "pdf", "txt", "text", "doc", "docx", "odt", "rtf", "md", "tex",
        "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub", "mobi",
        "azw3", "json", "xml", "yaml", "yml", "log", "zip", "rar", "7z",
        "tar", "gz", "tgz"
    )

    private const val MAX_FILES_ITEMS = 2_000

    /**
     * Lists documents / APKs from the Files collection on every storage
     * volume. Unlike the media collections, Files indexes non-media types;
     * selection is by extension and MIME (some providers mislabel APKs, so
     * both are checked). Trashed rows are excluded by the provider's default
     * match mode, and explicitly filtered on Android 11+ where the column
     * exists.
     */
    private fun queryFiles(context: Context): List<MediaItem> {
        val extensions = DOCUMENT_EXTENSIONS + "apk"
        val byExtension = extensions.joinToString(" OR ") {
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.${it}'"
        }
        // MATCH_EXCLUDE via query args is the documented way to leave trashed
        // rows out. A literal IS_TRASHED=0 selection matches nothing on
        // providers that report NULL for non-media rows — which is why the
        // document list went empty.
        val selection = buildString {
            append("(")
            append(byExtension)
            append(
                " OR ${MediaStore.MediaColumns.MIME_TYPE}='application/vnd.android.package-archive'" +
                    " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'text/%'" +
                    " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/pdf'" +
                    " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/msword'" +
                    " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/vnd.ms-%'" +
                    " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/vnd.openxmlformats-%'" +
                    " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/epub%'" +
                    " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/zip'" +
                    " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/x-7z%'"
            )
            append(")")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.DATA
        )

        // "external" always works for the primary volume; some OEMs ignore
        // the StorageManager volume names, so include both and dedup by URI.
        val volumes = buildList {
            add("external")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val manager = context.getSystemService(android.os.storage.StorageManager::class.java)
                    manager.storageVolumes.mapNotNull { it.mediaStoreVolumeName }
                }.getOrDefault(emptyList()).forEach { if (it !in this) add(it) }
            }
        }

        val queryArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )
            }
        } else null

        val items = ArrayList<MediaItem>()
        val seen = HashSet<String>()
        volumes.forEach { volume ->
            val uri = MediaStore.Files.getContentUri(volume)
            val cursor = runCatching {
                if (queryArgs != null) {
                    context.contentResolver.query(uri, projection, queryArgs, null)
                } else {
                    context.contentResolver.query(
                        uri, projection, selection, null,
                        "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                    )
                }
            }.getOrNull()
            runCatching {
                cursor?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val relativeCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val volumeCol = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                    val bucketCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val bucketIdCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                    val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)

                    while (c.moveToNext() && items.size < MAX_FILES_ITEMS) {
                        val id = c.getLong(idCol)
                        val itemUri = Uri.withAppendedPath(uri, id.toString())
                        if (!seen.add(itemUri.toString())) continue
                        val name = c.getString(nameCol) ?: "Unknown"
                        val data = if (dataCol >= 0) c.getString(dataCol) else null
                        // Providers often leave RELATIVE_PATH / BUCKET empty for
                        // non-media rows; the real DATA path still tells us
                        // which folder the file lives in.
                        val relative = (if (relativeCol >= 0) c.getString(relativeCol) else null)
                            ?: data?.let(::relativeDirectoryFromData)
                        val bucket = (if (bucketCol >= 0) c.getString(bucketCol) else null)
                            ?: relative?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                        val mime = c.getString(mimeCol)
                        val itemKind = if (name.endsWith(".apk", ignoreCase = true) ||
                            mime == "application/vnd.android.package-archive"
                        ) MediaKind.APK else MediaKind.DOCUMENT
                        items.add(
                            MediaItem(
                                id = id,
                                uri = itemUri,
                                kind = itemKind,
                                name = name,
                                mimeType = mime
                                    ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                                    ?: SystemTrash.mimeTypeForName(name),
                                sizeBytes = c.getLong(sizeCol),
                                dateAdded = c.getLong(addedCol),
                                dateModified = c.getLong(modifiedCol),
                                durationMs = null,
                                width = null,
                                height = null,
                                relativePath = relative,
                                volumeName = if (volumeCol >= 0) c.getString(volumeCol) else volume,
                                bucket = bucket,
                                bucketId = if (bucketIdCol >= 0 && !c.isNull(bucketIdCol)) {
                                    c.getLong(bucketIdCol).takeIf { it != 0L }
                                } else null,
                                title = null,
                                artist = null,
                                album = null,
                                absolutePath = data
                            )
                        )
                    }
                }
            }
        }
        return items
    }

    /** Directory portion of a DATA path, relative to the volume root. */
    private fun relativeDirectoryFromData(data: String): String? {
        val normalized = data.replace('\\', '/')
        val dir = normalized.substringBeforeLast('/', "")
        return when {
            dir.startsWith("/storage/emulated/0/") ->
                dir.removePrefix("/storage/emulated/0/") + "/"
            dir.startsWith("/sdcard/") ->
                dir.removePrefix("/sdcard/") + "/"
            dir.startsWith("/storage/") -> {
                val parts = dir.removePrefix("/storage/").split('/')
                if (parts.size >= 2) parts.drop(1).joinToString("/") + "/" else null
            }
            else -> null
        }?.takeIf { it != "/" && it.isNotBlank() }
    }

    private fun queryCollection(context: Context, kind: MediaKind): List<MediaItem> {
        val uri = when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaKind.DOCUMENT, MediaKind.APK -> MediaStore.Files.getContentUri("external")
        }

        // Audio belongs in its real folders too, so the bucket columns are
        // queried for every kind.
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.VOLUME_NAME)
            if (kind == MediaKind.AUDIO) {
                add(MediaStore.MediaColumns.TITLE)
                add(MediaStore.MediaColumns.ARTIST)
                add(MediaStore.MediaColumns.ALBUM)
                add(MediaStore.MediaColumns.DURATION)
            } else {
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
                if (kind == MediaKind.VIDEO) add(MediaStore.MediaColumns.DURATION)
            }
        }.toTypedArray()

        val items = ArrayList<MediaItem>()
        runCatching {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                val widthCol = if (kind == MediaKind.AUDIO) -1 else cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightCol = if (kind == MediaKind.AUDIO) -1 else cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val bucketIdCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val volumeNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                val durationCol = if (kind == MediaKind.IMAGE) -1 else cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val titleCol = if (kind == MediaKind.AUDIO) cursor.getColumnIndex(MediaStore.MediaColumns.TITLE) else -1
                val artistCol = if (kind == MediaKind.AUDIO) cursor.getColumnIndex(MediaStore.MediaColumns.ARTIST) else -1
                val albumCol = if (kind == MediaKind.AUDIO) cursor.getColumnIndex(MediaStore.MediaColumns.ALBUM) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    items.add(
                        MediaItem(
                            id = id,
                            uri = Uri.withAppendedPath(uri, id.toString()),
                            kind = kind,
                            name = cursor.getString(nameCol) ?: "Unknown",
                            mimeType = cursor.getString(mimeCol) ?: when (kind) {
                                MediaKind.IMAGE -> "image/*"
                                MediaKind.VIDEO -> "video/*"
                                MediaKind.AUDIO -> "audio/*"
                                MediaKind.DOCUMENT -> "application/octet-stream"
                                MediaKind.APK -> "application/vnd.android.package-archive"
                            },
                            sizeBytes = cursor.getLong(sizeCol),
                            dateAdded = cursor.getLong(addedCol),
                            dateModified = cursor.getLong(modifiedCol),
                            durationMs = if (durationCol >= 0) cursor.getLong(durationCol).takeIf { it > 0 } else null,
                            width = if (widthCol >= 0) cursor.getInt(widthCol).takeIf { it > 0 } else null,
                            height = if (heightCol >= 0) cursor.getInt(heightCol).takeIf { it > 0 } else null,
                            relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null,
                            volumeName = if (volumeNameCol >= 0) cursor.getString(volumeNameCol) else null,
                            bucket = if (bucketCol >= 0) cursor.getString(bucketCol) else null,
                            bucketId = if (bucketIdCol >= 0 && !cursor.isNull(bucketIdCol)) {
                                cursor.getLong(bucketIdCol).takeIf { it != 0L }
                            } else {
                                null
                            },
                            title = if (titleCol >= 0) cursor.getString(titleCol) else null,
                            artist = if (artistCol >= 0) cursor.getString(artistCol) else null,
                            album = if (albumCol >= 0) cursor.getString(albumCol) else null
                        )
                    )
                    if (items.size >= MAX_ITEMS) break
                }
            }
        }
        return items
    }

    /**
     * Creates a real directory in shared external storage: `DCIM/<name>/`.
     *
     * Empty folders cannot be represented in MediaStore — it is an index of
     * media, not of directories. But inserting a file into a non-existent
     * RELATIVE_PATH makes the system create the directories, so a 1×1 PNG is
     * inserted and then deleted, leaving the real folder behind. Works from
     * Android 10 (API 29), where creating your own media files needs no
     * extra permission; below that there is no clean route without the
     * all-files access permission.
     */
    suspend fun createPublicFolder(context: Context, name: String): Boolean =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val png = android.util.Base64.decode(MARKER_PNG_BASE64, android.util.Base64.DEFAULT)

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, ".empty_folder_marker.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$name/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false

            val written = runCatching {
                resolver.openOutputStream(uri)?.use { out -> out.write(png) }
                true
            }.getOrDefault(false)

            if (!written) {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext false
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
            // The folder survives; only the marker file goes away.
            runCatching { resolver.delete(uri, null, null) }
            true
        }

    /** True when the media item is an SVG image — BitmapFactory cannot decode these. */
    fun isSvgItem(item: MediaItem): Boolean =
        item.mimeType.contains("svg", ignoreCase = true) ||
            item.name.endsWith(".svg", ignoreCase = true)

    /** True when the item is an animated GIF (first-frame thumbnails look broken). */
    fun isGifItem(item: MediaItem): Boolean =
        item.mimeType.equals("image/gif", ignoreCase = true) ||
            item.name.endsWith(".gif", ignoreCase = true)

    /**
     * Document thumbnail, MaterialFiles-style: PDFs render their first page
     * with PdfRenderer; other document types fall back to a MIME icon in the
     * UI. Runs off the main thread and never throws.
     */
    suspend fun loadDocumentThumbnail(context: Context, item: MediaItem, size: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (item.kind != MediaKind.DOCUMENT) return@withContext null
            if (!item.mimeType.equals("application/pdf", ignoreCase = true) &&
                !item.name.endsWith(".pdf", ignoreCase = true)
            ) return@withContext null
            runCatching {
                val fd = context.contentResolver.openFileDescriptor(item.uri, "r") ?: return@runCatching null
                fd.use { descriptor ->
                    val renderer = PdfRenderer(descriptor)
                    try {
                        if (renderer.pageCount == 0) return@use null
                        val page = renderer.openPage(0)
                        try {
                            val height = if (page.width > 0) {
                                (size.toFloat() * page.height / page.width).toInt()
                                    .coerceIn(1, size * 2)
                            } else size
                            val bitmap = Bitmap.createBitmap(size, height, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        } finally {
                            page.close()
                        }
                    } finally {
                        renderer.close()
                    }
                }
            }.getOrNull()
        }

    /**
     * Decodes an animated GIF into a started [AnimatedImageDrawable] (API 28+),
     * or null when the source is not an animated GIF and the static thumbnail
     * should be used instead. The drawable owns its animation loop.
     */
    fun loadAnimatedGif(context: Context, item: MediaItem): Drawable? {
        if (!isGifItem(item) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, item.uri)
            val drawable = ImageDecoder.decodeDrawable(source)
            if (drawable is AnimatedImageDrawable) {
                drawable.start()
                drawable
            } else null
        }.getOrNull()
    }

    /**
     * Opens an SVG from a content URI and rasterizes it into a Bitmap,
     * bounded to [maxEdge] on the longest side.
     */
    private fun decodeSvgFrom(context: Context, item: MediaItem, maxEdge: Int): Bitmap? =
        runCatching {
            context.contentResolver.openInputStream(item.uri)?.use { decodeSvg(it, maxEdge) }
        }.getOrNull()

    /** Rasterizes an SVG stream into a Bitmap via AndroidSVG. */
    private fun decodeSvg(stream: java.io.InputStream, maxEdge: Int): Bitmap? {
        val svg = com.caverock.androidsvg.SVG.getFromInputStream(stream) ?: return null
        val picture = svg.renderToPicture()

        var width = picture.width
        var height = picture.height
        if (width <= 0 || height <= 0) {
            // Some SVGs carry only a viewBox and no intrinsic size.
            width = maxEdge
            height = maxEdge
        }
        val scale = if (maxOf(width, height) > maxEdge) maxEdge.toFloat() / maxOf(width, height) else 1f
        val bw = (width * scale).toInt().coerceAtLeast(1)
        val bh = (height * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.TRANSPARENT)
        canvas.scale(scale, scale)
        canvas.drawPicture(picture)
        return bitmap
    }

    /**
     * Best-effort absolute path of the folder an item sits in.
     *
     * MediaStore has no per-folder row, so the address is reconstructed from
     * VOLUME_NAME + RELATIVE_PATH, which are recorded per item — every item
     * in one bucket agrees on both.
     */
    fun folderAddress(volumeName: String?, relativePath: String?): String? {
        val relative = relativePath?.takeIf { it.isNotBlank() }?.trimStart('/') ?: return null
        val root = when (volumeName?.lowercase(Locale.US)) {
            null, "external_primary" -> "/storage/emulated/0"
            else -> "/storage/$volumeName"
        }
        return "$root/${relative.trimEnd('/')}/"
    }

    /**
     * The absolute path a MediaStore row lives at.
     *
     * DATA is deprecated but is the only honest bridge from a content row to
     * its file — and with all-files access it is exactly the bridge a file
     * manager uses. Works for any media kind, including cloud placeholders
     * (the path exists but is unreadable via regular open(), which callers
     * must handle).
     */
    /** Absolute path for file operations: real path first, MediaStore DATA second. */
    fun filePathFor(context: Context, item: MediaItem): String? =
        item.absolutePath ?: runCatching {
            context.contentResolver
                .query(item.uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    /**
     * Decodes a small thumbnail for grid tiles, or the album art for audio.
     *
     * `ContentResolver.loadThumbnail` (API 29+) handles every media type and
     * does its own scaling; older versions fall back to the legacy thumbnail
     * tables for images and videos, and to null for audio.
     */
    @Suppress("DEPRECATION")
    suspend fun loadThumbnail(context: Context, item: MediaItem, size: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (isSvgItem(item)) {
                    return@withContext decodeSvgFrom(context, item, 256)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val bitmap = context.contentResolver.loadThumbnail(
                        item.uri,
                        android.util.Size(size, size),
                        null
                    )
                    // loadThumbnail does not promise EXIF-normalised output on
                    // every source, so normalise here: a rotated thumbnail in
                    // the grid reads as broken rather than as a photo.
                    applyExifRotation(context, item.uri, bitmap)
                } else {
                    when (item.kind) {
                        MediaKind.IMAGE -> MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver, item.id,
                            MediaStore.Images.Thumbnails.MINI_KIND, null
                        )
                        MediaKind.VIDEO -> MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver, item.id,
                            MediaStore.Video.Thumbnails.MINI_KIND, null
                        )
                        MediaKind.AUDIO -> null
                        MediaKind.DOCUMENT, MediaKind.APK -> null
                    }
                }
            }.getOrNull()
        }

    /**
     * Decodes a full-size image for the viewer, downsampled to [maxEdge] and
     * EXIF-normalised so it opens upright.
     *
     * The provider stream is never decoded directly. Those streams are often
     * non-seekable pipes on OEM MediaStores, where `decodeStream` returns
     * null or stalls — which is why tapping a photo appeared to load forever
     * while audio and video (played through a file descriptor) worked. The
     * editor's own loader solves the same problem by copying to an app-private
     * file first; this follows that pattern into a self-cleaning temp file.
     */
    suspend fun loadFullImage(context: Context, item: MediaItem, maxEdge: Int = 2560): Bitmap? =
        withContext(Dispatchers.IO) {
            if (isSvgItem(item)) {
                // SVGs bypass the bitmap pipeline entirely: decode to Bitmap
                // through AndroidSVG instead of the failing BitmapFactory path.
                return@withContext decodeSvgFrom(context, item, SVG_MAX_EDGE)
            }

            val temp = runCatching {
                java.io.File.createTempFile("gallery_full_", ".img", context.cacheDir)
            }.getOrNull() ?: return@withContext null

            val decoded = runCatching {
                val copied = context.contentResolver.openInputStream(item.uri)?.use { input ->
                    java.io.FileOutputStream(temp).use { out ->
                        input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    }
                    temp.length() > 0
                } ?: false

                if (!copied) return@runCatching null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

                var sample = 1
                var longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                while (longEdge / 2 >= maxEdge) {
                    longEdge /= 2
                    sample *= 2
                }

                BitmapFactory.decodeFile(
                    temp.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            }.getOrNull()

            val oriented = decoded?.let {
                rotateBy(it, readRotationDegrees { ExifInterface(temp.absolutePath) })
            }
            // The original is never modified; the staging file goes away with it.
            temp.delete()
            oriented
        }

    /** Rotates [bitmap] to match the EXIF orientation tag, if any. */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap =
        rotateBy(
            bitmap,
            readRotationDegrees {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        ExifInterface(stream)
                    }
                }.getOrNull()
            }
        )

    /** Reads the EXIF orientation tag in degrees; 0 on any failure. */
    private fun readRotationDegrees(read: () -> ExifInterface?): Int =
        runCatching {
            when (
                read()?.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)

    private fun rotateBy(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
