package com.alex.photoeditor.core

import android.content.Context
import java.io.File

/**
 * Deletes leftover working files from the app's own cache.
 *
 * APK icon inspection and the installer fallback copy whole APKs into the
 * cache, and the image loader stages full-size copies there. They are removed
 * after use, but a crash or a killed process can leave them behind — on a
 * phone with large APKs that is easily hundreds of megabytes.
 */
object StorageCleanup {

    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Removes stale temp files. Returns bytes reclaimed. */
    fun sweepCache(context: Context): Long {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val dirs = listOf(context.cacheDir, File(context.cacheDir, "share"))
        var freed = 0L
        dirs.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val stale = file.lastModified() < cutoff
                val workingCopy = file.name.startsWith("apk-icon-") ||
                    file.name.startsWith("gallery_full_") ||
                    file.name.startsWith("apk-preview-")
                if (stale || workingCopy) {
                    val size = file.length()
                    if (runCatching { file.delete() }.getOrDefault(false)) freed += size
                }
            }
        }
        return freed
    }
}
