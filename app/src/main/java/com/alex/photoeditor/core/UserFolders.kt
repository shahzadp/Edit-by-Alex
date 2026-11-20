package com.alex.photoeditor.core

import android.content.Context

/**
 * The user's own named folders — the ones they created even before anything
 * moves into them.
 *
 * MediaStore cannot represent an empty folder: it is an index of media, so a
 * folder only appears once a file lives in it. Storing just the names here
 * lets the gallery show a freshly created, still-empty folder in every tab,
 * and merge the two sources by name once media moves in.
 */
object UserFolders {

    private const val PREFS = "gallery_user_folders"
    private const val KEY = "names"
    private const val MAX_NAME_LENGTH = 48

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The current user folder names, sorted case-insensitively. */
    fun list(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    /** Adds a folder, or replaces it case-insensitively. False if the name is not usable. */
    fun add(context: Context, rawName: String): Boolean {
        val name = rawName.trim()
        if (!usable(name)) return false

        val current = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        // Upsert with case-insensitive comparison so "camera" and "Camera" don't fork.
        val next = current.filterNot { it.equals(name, ignoreCase = true) }.toMutableSet()
        next.add(name)
        prefs(context).edit().putStringSet(KEY, next).apply()
        return true
    }

    /** Renames a tracked folder, keeping the rest of the list intact. */
    fun rename(context: Context, oldName: String, newRawName: String): Boolean {
        val newName = newRawName.trim()
        if (!usable(newName)) return false
        val current = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        if (current.none { it.equals(oldName, ignoreCase = true) }) return false
        val next = current.filterNot { it.equals(oldName, ignoreCase = true) }
            .filterNot { it.equals(newName, ignoreCase = true) }
            .toMutableSet()
        next.add(newName)
        prefs(context).edit().putStringSet(KEY, next).apply()
        return true
    }

    private fun usable(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return false
        // Control characters would break persistence and path handling.
        if (name.any { it.isISOControl() }) return false
        // These path segments would escape the intended directory.
        if (name.contains('/') || name.contains('\\') || name == "." || name == "..") return false
        return true
    }

    fun remove(context: Context, name: String) {
        val current = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        prefs(context).edit()
            .putStringSet(KEY, current.filterNot { it.equals(name, ignoreCase = true) }.toSet())
            .apply()
    }
}
