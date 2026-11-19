package com.alex.photoeditor.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates the real directories user folders live in.
 *
 * The user asked for folders at the root of shared storage — the same place
 * WhatsApp and Telegram put theirs — like `/storage/emulated/0/<name>/`. On
 * Android 11+ that is only possible with the "All files access" special
 * permission, granted once from the app's settings page.
 *
 * Without it, the only directories the app may create are inside standard
 * media roots, so the fallback creates the folder under `DCIM/<name>/`
 * through the MediaStore marker trick instead — same folder, different root,
 * no permissions needed.
 */
object FolderMaker {

    sealed interface Outcome {
        /** Folder created at the shared-storage root (all-files granted). */
        data object RootCreated : Outcome

        /** No all-files permission; created DCIM/<name>/ instead. */
        data object DcimFallback : Outcome

        /** Neither route worked. */
        data object Failed : Outcome
    }

    /** True when this app may create and move files anywhere on shared storage. */
    fun hasAllFilesAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Opens the app's "All files access" page. */
    fun openAllFilesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        runCatching { context.startActivity(intent) }
    }

    /** The root of shared storage, e.g. /storage/emulated/0. */
    fun sharedStorageRoot(): java.io.File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).parentFile
            ?: java.io.File("/storage/emulated/0")

    suspend fun create(context: Context, name: String): Outcome = withContext(Dispatchers.IO) {
        if (hasAllFilesAccess(context)) {
            val dir = java.io.File(sharedStorageRoot(), name)
            if (dir.exists() || dir.mkdirs()) {
                return@withContext Outcome.RootCreated
            }
            return@withContext Outcome.Failed
        }
        if (MediaStoreRepository.createPublicFolder(context, name)) {
            Outcome.DcimFallback
        } else {
            Outcome.Failed
        }
    }

    /**
     * Moves [items] into `<root>/<name>/` via the filesystem, then rescans so
     * MediaStore sees the files at their new paths immediately.
     *
     * Requires all-files access. Returns how many files moved.
     */
    suspend fun moveAll(
        context: Context,
        items: List<MediaItem>,
        name: String
    ): Int = withContext(Dispatchers.IO) {
        val destDir = java.io.File(sharedStorageRoot(), name)
        destDir.mkdirs()

        var moved = 0
        val destinations = mutableListOf<String>()
        items.forEach { item ->
            val target = moveOne(context, item, destDir) ?: return@forEach
            destinations.add(target.absolutePath)
            moved++
        }
        if (destinations.isNotEmpty()) {
            MediaScannerConnectionAdapter.scan(context, destinations)
        }
        moved
    }

    /** Renames [item] into [destDir]; false when anything failed. */
    private fun moveOne(context: Context, item: MediaItem, destDir: java.io.File): java.io.File? {
        val source = java.io.File(dataPathOf(context, item) ?: return null)
        if (!source.exists()) return null

        val base = source.nameWithoutExtension
        val ext = source.extension.let { if (it.isBlank()) "" else ".$it" }
        var target = java.io.File(destDir, source.name)
        var n = 1
        while (target.exists()) {
            target = java.io.File(destDir, "$base ($n)$ext")
            n++
        }

        return if (source.renameTo(target)) target else null
    }

    /**
     * The absolute path a MediaStore row lives at.
     *
     * DATA is deprecated but is the only honest bridge from a content row to
     * its file — and with all-files access it is exactly the bridge a file
     * manager uses.
     */
    private fun dataPathOf(context: Context, item: MediaItem): String? =
        MediaStoreRepository.filePathFor(context, item)

    /** Thin wrapper so callers don't need MediaScannerConnection boilerplate. */
    private object MediaScannerConnectionAdapter {
        fun scan(context: Context, paths: List<String>) {
            android.media.MediaScannerConnection.scanFile(
                context,
                paths.toTypedArray(),
                null,
                null
            )
        }
    }
}
