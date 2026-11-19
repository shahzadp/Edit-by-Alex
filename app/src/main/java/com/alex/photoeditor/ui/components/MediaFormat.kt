package com.alex.photoeditor.ui.components

/** Formats a millisecond duration as m:ss, or h:mm:ss past the hour. */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Formats a byte count as KB / MB / GB, whichever reads naturally. */
internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** Formats MediaStore's seconds-since-epoch date column. */
internal fun formatDate(seconds: Long): String {
    if (seconds <= 0) return "Unknown date"
    val millis = seconds * 1000
    return java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}
