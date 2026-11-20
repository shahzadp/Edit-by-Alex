package com.alex.photoeditor.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The app's trash bin.
 *
 * Deleting media with the system Trash (createTrashRequest) shows files in
 * the system recycle app, but there is no reliable public way to enumerate
 * trashed items in-app — so restore/delete-forever would mean punting the
 * user to a different screen controlled by the device maker. Instead the
 * gallery owns a private trash directory: files move into it with a JSON
 * index recording where they came from, and they can be restored or deleted
 * for real from inside the app.
 *
 * Trashed files deliberately live in app-private storage (not shared
 * storage), so they never re-appear in the gallery, never leak into other
 * file managers, and never corrupt any bucket — until restored.
 */
object TrashStore {

    private const val DIR_NAME = "trash"
    private const val INDEX_NAME = "trash.json"

    /** Bin files older than this are removed automatically. */
    private const val RETENTION_DAYS = 7L
    /** Hard cap on the bin; oldest files go first when exceeded. */
    private const val MAX_TRASH_BYTES = 512L * 1024 * 1024

    /** One binned file. */
    @Serializable
    data class TrashEntry(
        val id: String,
        val name: String,
        val kind: String,
        val mimeType: String,
        val sizeBytes: Long,
        /** Absolute path inside the app-private trash directory. */
        val storedPath: String,
        /** Absolute path it came from, for restore to recreate it in place. */
        val sourcePath: String,
        val bucket: String?,
        val deletedAt: Long
    )

    /** The directory trashed files live in. */
    fun trashDir(context: Context): File =
        File(context.filesDir, DIR_NAME)

    /**
     * Moves [items] into the trash. Returns how many files were actually
     * moved; the caller refreshes the gallery and forgets any user-created
     * folders that are now empty.
     */
    suspend fun importItems(context: Context, items: List<MediaItem>): Int = withContext(Dispatchers.IO) {
        val dir = trashDir(context)
        if (!dir.exists() && !dir.mkdirs()) return@withContext 0

        val entries = readIndex(context).toMutableList()
        var moved = 0
        items.forEach { item ->
            val source = MediaStoreRepository.filePathFor(context, item)?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val sourceFile = File(source)
            if (!sourceFile.exists()) return@forEach

            val id = java.util.UUID.randomUUID().toString()
            val stored = File(dir, id + "_" + sourceFile.name)
            if (!moveFile(sourceFile, stored)) return@forEach

            entries.add(
                TrashEntry(
                    id = id,
                    name = item.name,
                    kind = item.kind.name,
                    mimeType = item.mimeType,
                    sizeBytes = sourceFile.length(),
                    storedPath = stored.absolutePath,
                    sourcePath = source,
                    bucket = item.bucket,
                    deletedAt = System.currentTimeMillis()
                )
            )
            moved++
        }
        writeIndex(context, entries)
        cleanup(context)
        moved
    }

    /**
     * Applies retention and the size cap. Returns the number of files erased.
     * Called on launch, on refresh and after imports, so the bin cannot grow
     * without bound on a device with lots of deletions.
     */
    fun cleanup(context: Context): Int = synchronized(this) {
        val entries = readIndex(context)
        val dir = trashDir(context)
        val now = System.currentTimeMillis()
        // Disk is authoritative: an index can under-report sizes (or list
        // files that no longer exist), which used to keep the bin over cap.
        val allFiles = dir.listFiles().orEmpty().filter { it.isFile && it.name != INDEX_NAME }
        var diskBytes = allFiles.sumOf { it.length() }
        val removed = mutableListOf<String>()

        fun erase(file: File) {
            val length = runCatching { file.length() }.getOrDefault(0)
            if (runCatching { file.delete() }.getOrDefault(false)) {
                removed.add(file.name)
                diskBytes -= length
            }
        }

        // 1. Expired by age (index timestamp or real file age).
        entries.filter {
            now - it.deletedAt > RETENTION_DAYS * 24 * 60 * 60 * 1000L ||
                File(it.storedPath).let { f -> !f.exists() || now - f.lastModified() > RETENTION_DAYS * 24 * 60 * 60 * 1000L }
        }.forEach { erase(File(it.storedPath)) }

        // 2. Size cap, oldest first, until the actual disk usage fits.
        val remaining = entries.filterNot { File(it.storedPath).name in removed }
            .sortedByDescending { it.deletedAt }
            .toMutableList()
        while (diskBytes > MAX_TRASH_BYTES && remaining.isNotEmpty()) {
            val oldest = remaining.removeAt(remaining.lastIndex)
            erase(File(oldest.storedPath))
        }

        // 3. Orphans (lost/corrupt index). A minute's grace avoids racing an
        // in-flight import that has moved a file but not yet written the index.
        val indexed = remaining.map { File(it.storedPath).name }.toSet()
        allFiles.forEach { file ->
            if (file.name !in indexed && now - file.lastModified() > 60_000) erase(file)
        }

        val kept = remaining.filter { File(it.storedPath).exists() }
        if (removed.isNotEmpty() || kept.size != entries.size) {
            writeIndex(context, kept)
        }
        removed.size
    }

    /** Everything in the bin, newest first. */
    fun list(context: Context): List<TrashEntry> =
        readIndex(context).sortedByDescending { it.deletedAt }

    /** Total size of all binned files, for the "N items, X MB" line. */
    fun totalBytes(context: Context): Long =
        readIndex(context).sumOf { it.sizeBytes }

    /**
     * Restores [entry] by moving it back to its original path. If the name
     * is taken, a numeric suffix is appended. Returns the destination path on
     * success.
     */
    suspend fun restore(context: Context, entry: TrashEntry): String? = withContext(Dispatchers.IO) {
        val source = File(entry.storedPath)
        if (!source.exists()) return@withContext null

        val destination = availableDestination(File(entry.sourcePath))
        destination.parentFile?.mkdirs()
        if (!moveFile(source, destination)) return@withContext null

        removeFromIndex(context, entry.id)
        // Return the path — the caller scans and refreshes so the gallery
        // picks up the file as soon as the system has it indexed.
        destination.absolutePath
    }

    /** Permanently deletes [entry]. Cannot be undone. */
    suspend fun deleteForever(context: Context, entry: TrashEntry): Boolean = withContext(Dispatchers.IO) {
        val deleted = File(entry.storedPath).runCatching { delete() }.getOrDefault(false)
        removeFromIndex(context, entry.id)
        deleted
    }

    /** Empties the bin. Returns how many files were deleted. */
    suspend fun empty(context: Context): Int = withContext(Dispatchers.IO) {
        val entries = readIndex(context)
        var deleted = 0
        entries.forEach { entry ->
            if (File(entry.storedPath).runCatching { delete() }.getOrDefault(false)) deleted++
        }
        writeIndex(context, emptyList())
        deleted
    }

    // ------------------------------------------------------------ internals

    private fun readIndex(context: Context): List<TrashEntry> {
        val file = File(trashDir(context), INDEX_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            Json.decodeFromString<List<TrashEntry>>(file.readText())
        }.getOrElse {
            // A corrupt index would strand files forever; log and start empty.
            // The files themselves are still restorable by hand if anything
            // ever needs them.
            emptyList()
        }
    }

    private fun writeIndex(context: Context, entries: List<TrashEntry>) {
        val dir = trashDir(context)
        if (!dir.exists() && !dir.mkdirs()) return
        val file = File(dir, INDEX_NAME)
        runCatching {
            file.writeText(Json.encodeToString(entries))
        }
    }

    private fun removeFromIndex(context: Context, id: String) {
        val remaining = readIndex(context).filterNot { it.id == id }
        writeIndex(context, remaining)
    }

    private fun availableDestination(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isBlank()) "" else ".$it" }
        var n = 1
        while (true) {
            val candidate = File(file.parentFile, "$base ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /**
     * Moves [source] to [dest] — across filesystems.
     *
     * `File.renameTo` only works inside one filesystem. Internal app
     * storage (`/data/.../files`) and shared storage (`/storage/emulated/0`)
     * are different mounts on most devices, so a bare renameTo silently
     * fails — the original stays put and the trash reports nothing moved.
     * Falls back to copy + delete, which works everywhere.
     */
    fun moveFile(source: File, dest: File): Boolean {
        if (source.renameTo(dest)) return true

        return runCatching {
            dest.parentFile?.mkdirs()
            source.inputStream().use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            if (dest.exists() && dest.length() == source.length() && dest.length() > 0) {
                source.delete()
                true
            } else {
                dest.delete()
                false
            }
        }.getOrDefault(false)
    }
}
