package com.alex.photoeditor.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.content.pm.PackageManager
import java.io.File

private const val MAX_APK_ICON_BYTES = 250L * 1024 * 1024

/** True when the row names/describes an Android package archive. */
internal fun isApkTrashFile(name: String, mimeType: String): Boolean =
    mimeType == "application/vnd.android.package-archive" ||
        name.endsWith(".apk", ignoreCase = true)

/**
 * Reads the app icon from a trashed APK, or null when the archive is
 * unreadable (e.g. another app's trash directory is mode 700) or too large.
 *
 * Rows trashed by this app are always readable. Rows trashed by other apps
 * fall back to a generic icon until the user restores them.
 */
internal fun loadApkIcon(context: Context, row: TrashRowData): Bitmap? {
    if (!isApkTrashFile(row.name, row.mimeType) || row.sizeBytes > MAX_APK_ICON_BYTES) return null

    val storedPath = row.appEntry?.storedPath ?: row.systemEntry?.path
    var tempCopied: File? = null
    val sourcePath = storedPath ?: run {
        val uri: Uri = row.systemEntry?.uri ?: return null
        val out = File(context.cacheDir, "apk-icon-${row.id.hashCode()}.apk")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return null
        }.isSuccess
        if (!ok) return null
        tempCopied = out
        out.absolutePath
    }

    @Suppress("DEPRECATION")
    return try {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(sourcePath, PackageManager.GET_META_DATA) ?: return null
        val appInfo = info.applicationInfo ?: return null
        // Archive apps need sourceDir pointed back at the archive.
        appInfo.sourceDir = sourcePath
        appInfo.publicSourceDir = sourcePath
        val drawable = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull() ?: return null
        drawableToBitmap(drawable)
    } finally {
        tempCopied?.delete()
    }
}

/** Handles every kind of drawable: bitmap, adaptive, vector. */
internal fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 192
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 192
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(canvas)
    return bmp
}
