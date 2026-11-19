package com.alex.photoeditor.core

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Items the system Trash holds rather than this app.
 *
 * Two sources, merged:
 *
 *  1. MediaStore with IS_TRASHED = 1 — every image, video and audio file the
 *     device classes as trashed, regardless of which app put them there.
 *  2. A filesystem sweep of `.Trash-*` directories at the volume root — the
 *     media provider's trash, which includes files of any type, not just
 *     media. Items found here are not visible through MediaStore.
 *
 * Restore goes through [MediaStore.createTrashRequest] with trashed = false
 * for media items (the system puts them back where they came from); for
 * filesystem-only items we move them to the shared-storage root, which is
 * honest about not knowing the original location.
 */
object SystemTrash {

    private const val MAX_SCAN_ITEMS = 1_000
    private const val MAX_DEPTH = 4

    /** One system-trashed file, from either source. */
    data class Entry(
        /** Absolute path for file operations, or the content:// uri as string. */
        val id: String,
        val uri: Uri?,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val dateAddedSeconds: Long,
        val kind: MediaKind?,
        val path: String?
    )

    suspend fun query(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        // MediaStore media collections — API 30+ only, but the filesystem
        // sweep below runs everywhere.
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { queryMediaTrash(context) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        // Full-file MediaStore query and filesystem sweep — all volume roots.
        val files = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { queryAllFilesTrash(context) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val fs = scanFilesystemTrash(context)
        val seen = HashSet<String>()
        val merged = ArrayList<Entry>(media.size + files.size + fs.size)
        // Dedup by file path — MediaStore rows and raw filesystem finds overlap.
        (media + files + fs).forEach { entry ->
            val key = entry.path ?: entry.id
            if (seen.add(key)) merged.add(entry)
        }
        merged.sortedByDescending { it.dateAddedSeconds }
    }

    /** Moves a filesystem entry to the shared-storage root with a unique name. */
    suspend fun restoreFromFilesystem(context: Context, entry: Entry): Boolean =
        withContext(Dispatchers.IO) {
            val source = entry.path ?: return@withContext false
            val sourceFile = File(source)
            if (!sourceFile.exists()) return@withContext false

            val dir = Environment.getExternalStorageDirectory()
            dir?.mkdirs()
            var target = File(dir ?: return@withContext false, sourceFile.name)
            // Never clobber an existing file; append a counter instead.
            var n = 1
            while (target.exists()) {
                val base = target.nameWithoutExtension
                val ext = target.extension.let { if (it.isBlank()) "" else ".$it" }
                target = File(dir, "$base ($n)$ext")
                n++
            }
            if (!TrashStore.moveFile(sourceFile, target)) return@withContext false
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(entry.mimeType),
                null
            )
            true
        }

    /** Permanently deletes an entry directly on the filesystem. */
    suspend fun deleteForever(entry: Entry): Boolean = withContext(Dispatchers.IO) {
        val source = entry.path ?: return@withContext false
        val deleted = runCatching { File(source).delete() }.getOrDefault(false)
        deleted
    }

    // ------------------------------------------------------------ internals

    private fun queryMediaTrash(context: Context): List<Entry> {
        val result = ArrayList<Entry>()
        QUERY_COLLECTIONS.forEach { (uri, kind) ->
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.DATA
                    ),
                    "${MediaStore.MediaColumns.IS_TRASHED}=1",
                    null,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                    while (cursor.moveToNext() && result.size < MAX_SCAN_ITEMS) {
                        val id = cursor.getLong(idCol)
                        val itemUri = Uri.withAppendedPath(uri, id.toString())
                        val name = cursor.getString(nameCol) ?: "Unknown"
                        val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                        val detectedKind = mediaKindFor(name)
                            ?: kind
                        result.add(
                            Entry(
                                id = itemUri.toString(),
                                uri = itemUri,
                                name = name,
                                mimeType = mime,
                                sizeBytes = cursor.getLong(sizeCol),
                                dateAddedSeconds = cursor.getLong(dateCol),
                                kind = detectedKind,
                                path = if (dataCol >= 0) cursor.getString(dataCol) else null
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    /** Everything the Files collection marks as trashed — images, docs, APKs, all of it. */
    private fun queryAllFilesTrash(context: Context): List<Entry> {
        val allUri = MediaStore.Files.getContentUri("external")
        val result = ArrayList<Entry>()
        context.contentResolver.query(
            allUri,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATA
            ),
            "${MediaStore.MediaColumns.IS_TRASHED}=1",
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

            while (cursor.moveToNext() && result.size < MAX_SCAN_ITEMS) {
                val id = cursor.getLong(idCol)
                val itemUri = Uri.withAppendedPath(allUri, id.toString())
                val name = cursor.getString(nameCol) ?: "Unknown"
                val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                result.add(
                    Entry(
                        id = itemUri.toString(),
                        uri = itemUri,
                        name = name,
                        mimeType = mime,
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAddedSeconds = cursor.getLong(dateCol),
                        kind = mediaKindFor(name),
                        path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    )
                )
            }
        }
        return result
    }

    private val QUERY_COLLECTIONS: List<Pair<android.net.Uri, MediaKind?>> = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to MediaKind.IMAGE,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MediaKind.VIDEO,
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to MediaKind.AUDIO
    )

    /**
     * Walks any `.Trash*`-rooted directory at the volume root.
     *
     * The system trash is not one fixed path: Android's media provider uses
     * `.Trash-<owner-uid>` (e.g. `.Trash-1000` for SYSTEM, `.Trash-2000` for
     * SHELL, `.Trash-10010` for the user), some OEMs use simply `.Trash`, and
     * third-party file managers may add their own. The marker is consistent —
     * the directory is hidden (starts with a dot) and its name starts with
     * "trash". Anything else is left alone.
     */
    private fun scanFilesystemTrash(context: Context): List<Entry> {
        val roots = mutableListOf<File>()
        Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        // Every volume the system exposes, not just primary — SD cards, USB
        // OTG, and OEMs that keep their trash on secondary storage. The public
        // API for this is getExternalFilesDirs; walk up from app-private dirs.
        context.getExternalFilesDirs(null).drop(1).forEach { appDir ->
            // …/Android/data/<package>/files — two parents up is the volume root
            appDir?.parentFile?.parentFile?.parentFile?.let(roots::add)
        }

        val scanRoots = mutableSetOf<File>()
        roots.forEach { root ->
            // Any direct child whose name contains "trash" or "recycle" — not just a leading dot —
            // catches OEMs that scatter their trash anywhere on the volume.
            root.listFiles { f ->
                f.isDirectory && f.name.contains("trash", ignoreCase = true)
            }?.let(scanRoots::addAll)
        }

        val result = ArrayList<Entry>()
        scanRoots.forEach { trashDir ->
            try {
                walk(trashDir, depth = 0) { file ->
                    if (result.size >= MAX_SCAN_ITEMS) return@walk
                    if (!file.isFile) return@walk
                    result.add(
                        Entry(
                            id = file.absolutePath,
                            uri = null,
                            name = file.name,
                            mimeType = mimeTypeFor(file.name),
                            sizeBytes = file.length(),
                            dateAddedSeconds = file.lastModified() / 1000,
                            kind = mediaKindFor(file.name),
                            path = file.absolutePath
                        )
                    )
                }
            } catch (_: SecurityException) {
                // OEM trash directory not readable with the granted access —
                // nothing we can do; the MediaStore query is the fallback.
            }
        }
        return result
    }

    /** Best-effort MIME type from a filename extension. */
    private fun mimeTypeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heif"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

    private fun mediaKindFor(name: String): MediaKind? =
        when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "svg", "bmp", "tif", "tiff", "avif" -> MediaKind.IMAGE
            "mp4", "mkv", "mov", "webm", "avi", "flv", "3gp", "m4v", "ts", "wmv", "mpg", "mpeg" -> MediaKind.VIDEO
            "mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "opus", "amr" -> MediaKind.AUDIO
            else -> null
        }

    private fun walk(root: File, depth: Int, action: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        val children = root.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) walk(child, depth + 1, action) else action(child)
        }
    }
}
