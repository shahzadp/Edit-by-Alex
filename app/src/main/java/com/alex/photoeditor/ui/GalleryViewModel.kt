package com.alex.photoeditor.ui

import android.app.Application
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alex.photoeditor.core.MediaItem
import com.alex.photoeditor.core.MediaKind
import com.alex.photoeditor.core.MediaStoreRepository
import com.alex.photoeditor.core.SystemTrash
import com.alex.photoeditor.core.StorageCleanup
import com.alex.photoeditor.core.TrashStore
import com.alex.photoeditor.core.UserFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tabs in the gallery screen. */
enum class GalleryTab(val label: String, val kind: MediaKind) {
    PHOTOS("Photos", MediaKind.IMAGE),
    VIDEOS("Videos", MediaKind.VIDEO),
    AUDIO("Audios", MediaKind.AUDIO),
    DOCUMENTS("Documents", MediaKind.DOCUMENT),
    APK("APK", MediaKind.APK)
}

/**
 * State for the gallery screen.
 *
 * Kept separate from [EditorViewModel] because the gallery has a completely
 * different lifecycle: it holds large lists of everything on the device, and
 * none of the editor's edit state. Sharing the editor ViewModel would tie
 * process death of a list query to a half-finished edit.
 *
 * All three tabs are loaded up front rather than on switch: the screen lets
 * the user swipe horizontally between tabs (a [HorizontalPager]), so sibling
 * pages need their data ready before the swipe lands, and "loading forever"
 * on a freshly revealed page is exactly the bug this avoids.
 *
 * Thumbnail decode lives in the tile composables, not here — the grid only
 * ever asks for what is on screen.
 */
class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    /** Which inner tab is selected, mirrored to the pager in the UI. */
    var tab by mutableStateOf(GalleryTab.PHOTOS)
        private set

    private val itemsMap = mutableStateMapOf<GalleryTab, List<MediaItem>>()
    private val loadingMap = mutableStateMapOf<GalleryTab, Boolean>()
    private val errorMap = mutableStateMapOf<GalleryTab, String?>()
    /**
     * Folder display names the user created themselves. Merged into the
     * folders view so a brand-new, still-empty folder is visible until media
     * is moved into it (MediaStore cannot index an empty folder).
     */
    var userFolders by mutableStateOf(emptySet<String>())
        private set

    /** True while the trash bin is open. */
    var showTrash by mutableStateOf(false)
        private set

    /**
     * (bucketKey, displayName) of the folder being viewed, or null. Kept in
     * the ViewModel so returning from the editor restores the gallery to the
     * exact folder the user came from, not just the tab.
     */
    var openFolderKey by mutableStateOf<Pair<String, String>?>(null)
        private set

    /** Everything in the bin, newest first. */
    var trashEntries by mutableStateOf<List<TrashStore.TrashEntry>>(emptyList())
        private set

    /**
     * Items the system Trash holds — media trashed by any app, plus raw
     * files in `.Trash-*` directories. These are NOT files this app deleted;
     * their original location is unknown except for media (where the system
     * answers restore for us).
     */
    var systemTrashItems by mutableStateOf<List<SystemTrash.Entry>>(emptyList())
        private set

    /** Total size of everything in the bin, app and system items combined. */
    val trashSizeBytes: Long
        get() = trashEntries.sumOf { it.sizeBytes } + systemTrashItems.sumOf { it.sizeBytes }

    private var loadGeneration = 0
    private val tabGenerations = HashMap<GalleryTab, Int>()

    /**
     * Re-queries ONE tab. Only needed when an operation creates a new file
     * (a copy) whose exact metadata we don't know locally. Ordinary edits,
     * deletes and restores update the visible list in place and never call
     * this — that was the source of unnecessary loading.
     */
    fun refreshTab(tab: GalleryTab, delayMs: Long = 600) {
        viewModelScope.launch {
            delay(delayMs)
            val generation = (tabGenerations[tab] ?: 0) + 1
            tabGenerations[tab] = generation
            loadingMap[tab] = true
            if (tab == GalleryTab.DOCUMENTS || tab == GalleryTab.APK) {
                MediaStoreRepository.invalidateFileCache()
            }
            val result = runCatching { MediaStoreRepository.query(getApplication(), tab.kind) }
            if (tabGenerations[tab] != generation) return@launch
            result.onSuccess { itemsMap[tab] = it }
            loadingMap[tab] = false
        }
    }

    /** Removes items from a tab's current list immediately. */
    fun removeItemsLocally(tab: GalleryTab, ids: Set<Long>) {
        itemsMap[tab] = itemsMap[tab].orEmpty().filterNot { it.id in ids }
    }

    /** Removes items from every tab (search results can span categories). */
    fun removeItemsByUris(uris: Set<android.net.Uri>) {
        GalleryTab.entries.forEach { tab ->
            itemsMap[tab] = itemsMap[tab].orEmpty().filterNot { it.uri in uris }
        }
    }

    /** Shows a renamed item immediately, wherever it lives. */
    fun renameItemLocallyByUri(uri: android.net.Uri, newName: String) {
        GalleryTab.entries.forEach { tab ->
            itemsMap[tab] = itemsMap[tab].orEmpty().map {
                if (it.uri == uri) it.copy(name = newName) else it
            }
        }
    }

    /** Inserts a just-restored file into its tab immediately. */
    fun addRestoredItem(entry: TrashStore.TrashEntry, destination: String) {
        val file = java.io.File(destination)
        val kind = runCatching { MediaKind.valueOf(entry.kind) }.getOrNull()
            ?: MediaKind.DOCUMENT
        val parent = file.parentFile
        val root = com.alex.photoeditor.core.FolderMaker.sharedStorageRoot().absolutePath
        val relative = parent?.absolutePath?.removePrefix(root)?.trimStart('/')
            ?.takeIf { it.isNotBlank() }?.plus("/")
        val item = MediaItem(
            id = file.absolutePath.hashCode().toLong(),
            uri = runCatching {
                FileProvider.getUriForFile(
                    getApplication(), "${getApplication<Application>().packageName}.fileprovider", file
                )
            }.getOrNull() ?: return,
            kind = kind,
            name = file.name,
            mimeType = entry.mimeType,
            sizeBytes = file.length(),
            dateAdded = System.currentTimeMillis() / 1000,
            dateModified = file.lastModified() / 1000,
            durationMs = null,
            width = null,
            height = null,
            relativePath = relative,
            volumeName = "external",
            bucket = parent?.name,
            bucketId = parent?.absolutePath?.hashCode()?.toLong(),
            title = null,
            artist = null,
            album = null,
            absolutePath = file.absolutePath
        )
        val tab = when (kind) {
            MediaKind.IMAGE -> GalleryTab.PHOTOS
            MediaKind.VIDEO -> GalleryTab.VIDEOS
            MediaKind.AUDIO -> GalleryTab.AUDIO
            MediaKind.DOCUMENT -> GalleryTab.DOCUMENTS
            MediaKind.APK -> GalleryTab.APK
        }
        itemsMap[tab] = (itemsMap[tab].orEmpty() + item).sortedByDescending { it.dateAdded }
    }

    /** Inserts a newly copied file without re-querying the library. */
    fun addCopiedItem(source: MediaItem, destination: String) {
        val file = java.io.File(destination)
        if (!file.exists()) return
        val parent = file.parentFile
        val root = com.alex.photoeditor.core.FolderMaker.sharedStorageRoot().absolutePath
        val relative = parent?.absolutePath?.removePrefix(root)?.trimStart('/')
            ?.takeIf { it.isNotBlank() }?.plus("/")
        val item = source.copy(
            id = file.absolutePath.hashCode().toLong(),
            uri = runCatching {
                FileProvider.getUriForFile(
                    getApplication(), "${getApplication<Application>().packageName}.fileprovider", file
                )
            }.getOrNull() ?: return,
            name = file.name,
            sizeBytes = file.length(),
            dateAdded = System.currentTimeMillis() / 1000,
            dateModified = file.lastModified() / 1000,
            relativePath = relative,
            bucket = parent?.name,
            bucketId = parent?.absolutePath?.hashCode()?.toLong(),
            absolutePath = file.absolutePath
        )
        val tab = when (source.kind) {
            MediaKind.IMAGE -> GalleryTab.PHOTOS
            MediaKind.VIDEO -> GalleryTab.VIDEOS
            MediaKind.AUDIO -> GalleryTab.AUDIO
            MediaKind.DOCUMENT -> GalleryTab.DOCUMENTS
            MediaKind.APK -> GalleryTab.APK
        }
        itemsMap[tab] = (itemsMap[tab].orEmpty() + item).sortedByDescending { it.dateAdded }
    }

    /** Fast local-only trash index refresh (no system-trash query). */
    fun refreshTrashLocal() {
        trashEntries = TrashStore.list(getApplication())
    }

    /** Shows a new name immediately, before the full index catches up. */
    fun renameItemLocally(tab: GalleryTab, id: Long, newName: String) {
        itemsMap[tab] = itemsMap[tab].orEmpty().map {
            if (it.id == id) it.copy(name = newName) else it
        }
    }

    /**
     * Drops one system-trash row immediately, then reconciles in the
     * background — the restored file shouldn't wait for a full scan.
     */
    fun removeSystemTrashLocally(entry: SystemTrash.Entry) {
        systemTrashItems = systemTrashItems.filterNot { it.id == entry.id }
    }

    init {
        // Trashed files and leftover APK/icon copies are the two things that
        // can grow app data without limit; reclaim both on every launch.
        viewModelScope.launch(Dispatchers.IO) {
            TrashStore.cleanup(getApplication())
            StorageCleanup.sweepCache(getApplication())
        }
        refresh()
    }

    /**
     * Drops user-created folder entries whose directories no longer exist.
     *
     * Empty folders only exist in two places: `<storage root>/<name>/` and
     * `DCIM/<name>/` (the legacy marker-trick location). Once such a
     * directory is deleted elsewhere — another file manager, a cleanup app or
     * plain rm — the gallery used to show it forever as an empty folder.
     * Reconciling against the filesystem on every refresh keeps the list
     * honest without the user doing anything.
     */
    private fun pruneStaleUserFolders() {
        val root = com.alex.photoeditor.core.FolderMaker.sharedStorageRoot()
        val dcim = java.io.File(root, android.os.Environment.DIRECTORY_DCIM)
        val tracked = UserFolders.list(getApplication())
        val alive = tracked.filter { name ->
            java.io.File(root, name).exists() || java.io.File(dcim, name).exists()
        }.toSet()
        val stale = tracked - alive
        if (stale.isNotEmpty()) {
            stale.forEach { name -> UserFolders.remove(getApplication(), name) }
        }
    }

    /** Creates a user folder; false when the name is not usable. */
    fun addUserFolder(name: String): Boolean {
        if (!UserFolders.add(getApplication(), name)) return false
        userFolders = UserFolders.list(getApplication())
        return true
    }

    /** Forgets a user folder. Used when its real directory is deleted or emptied. */
    fun removeUserFolder(name: String) {
        UserFolders.remove(getApplication(), name)
        userFolders = UserFolders.list(getApplication())
    }

    fun openTrash() {
        showTrash = true
        refreshTrash()
    }

    fun closeTrash() {
        showTrash = false
    }

    fun openFolder(key: String, name: String) {
        openFolderKey = key to name
    }

    fun closeFolder() {
        openFolderKey = null
    }

    fun refreshTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            TrashStore.cleanup(getApplication())
            trashEntries = TrashStore.list(getApplication())
            systemTrashItems = SystemTrash.query(getApplication())
        }
    }

    /** Immediate retention/cap cleanup with a local-only index refresh. */
    fun cleanupTrashNow(): Int {
        val removed = TrashStore.cleanup(getApplication())
        trashEntries = TrashStore.list(getApplication())
        return removed
    }

    /** Permanently deletes a system-trash entry (file delete, URI fallback). */
    fun deleteSystemTrashEntryForever(entry: SystemTrash.Entry) {
        viewModelScope.launch(Dispatchers.IO) {
            when {
                entry.path != null -> java.io.File(entry.path).delete()
                entry.uri != null -> runCatching {
                    getApplication<android.app.Application>().contentResolver.delete(entry.uri, null, null)
                }
            }
            removeSystemTrashLocally(entry)
        }
    }

    /**
     * Restores a system-trashed entry, trying the strongest route first.
     *  1. Direct provider update (IS_TRASHED=0) — silent, works when all-files
     *     access is honored, puts the file back in its original location and
     *     clears the trash row in one step.
     *  2. Filesystem move to Downloads/Edit by Alex Restored/ — for trash this
     *     app can read.
     *  3. The system's own restore request (an IntentSender dialog, built by
     *     the caller through [onFallback]) — the only path for files owned
     *     by another app.
     */
    fun restoreSystemTrashEntry(
        entry: SystemTrash.Entry,
        onFallback: ((SystemTrash.Entry) -> Unit)? = null
    ) {
        val app = getApplication<android.app.Application>()
        viewModelScope.launch(Dispatchers.IO) {
            var detail: String? = null
            if (entry.uri != null) {
                val result = runCatching {
                    app.contentResolver.update(
                        entry.uri,
                        android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.IS_TRASHED, 0)
                        },
                        null,
                        null
                    )
                }
                val rows = result.getOrNull() ?: -1
                if (rows > 0) {
                    removeSystemTrashLocally(entry)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            app,
                            "Restored ${entry.name}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                detail = result.exceptionOrNull()?.message?.take(120) ?: "provider returned $rows"
            }

            val destination = SystemTrash.restoreFromFilesystem(app, entry)
            if (destination != null) {
                removeSystemTrashLocally(entry)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        app,
                        "Restored to $destination",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            if (onFallback != null && entry.uri != null) {
                withContext(Dispatchers.Main) { onFallback(entry) }
            } else {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        app,
                        "Couldn't restore this file. ${detail ?: "It was trashed by another app — restore it from the Files app."}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Restores a binned file to its original location, refreshing the gallery after the scan. */
    fun restoreTrashEntry(id: String) {
        val entry = trashEntries.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val restored = TrashStore.restore(getApplication(), entry)
            refreshTrashLocal()
            if (restored != null) {
                // Show the file immediately; the real library index catches
                // up in the background instead of blocking this tap.
                addRestoredItem(entry, restored)
                android.media.MediaScannerConnection.scanFile(
                    getApplication(),
                    arrayOf(restored),
                    null
                ) { _, _ -> }
            } else {
                // Nothing changed in the library — the visible lists stay put.
            }
        }
    }

    /** Permanently deletes a binned file. Cannot be undone. */
    fun deleteTrashEntryForever(id: String) {
        val entry = trashEntries.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            TrashStore.deleteForever(getApplication(), entry)
            refreshTrashLocal()
        }
    }

    /** Empties the whole bin, including system-trashed files. */
    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            TrashStore.empty(getApplication())
            systemTrashItems.forEach { entry ->
                when {
                    entry.path != null -> runCatching { java.io.File(entry.path).delete() }
                    entry.uri != null -> runCatching {
                        getApplication<android.app.Application>().contentResolver.delete(entry.uri, null, null)
                    }
                }
            }
            refreshTrashLocal()
            systemTrashItems = emptyList()
        }
    }

    fun itemsOf(target: GalleryTab): List<MediaItem> = itemsMap[target].orEmpty()

    fun isLoadingOf(target: GalleryTab): Boolean = loadingMap[target] == true

    fun errorOf(target: GalleryTab): String? = errorMap[target]

    /** True while any tab's query is still running — drives the linear indicator. */
    val isLoading: Boolean get() = GalleryTab.entries.any { loadingMap[it] == true }

    fun selectTab(next: GalleryTab) {
        if (next == tab) return
        // Switching tabs moves to a different kind of view; drop the folder drill-in.
        closeFolder()
        tab = next
    }

    /** Re-queries everything. Call after permission is granted or on pull-to-refresh. */
    fun refresh() {
        // Folders that were deleted elsewhere (another file manager, a
        // cleanup app, rm) must not linger in the gallery's own bookkeeping.
        pruneStaleUserFolders()
        userFolders = UserFolders.list(getApplication())
        MediaStoreRepository.invalidateFileCache()
        val generation = ++loadGeneration
        GalleryTab.entries.forEach { target ->
            loadingMap[target] = true
            errorMap[target] = null
            viewModelScope.launch {
                val result = runCatching {
                    MediaStoreRepository.query(getApplication(), target.kind)
                }
                // A refresh while a query was in flight must not paint stale
                // results over the fresh ones.
                if (generation != loadGeneration) return@launch
                result.onSuccess { list ->
                    itemsMap[target] = list
                }.onFailure {
                    errorMap[target] = "Could not read the library on this device."
                }
                loadingMap[target] = false
            }
        }
    }
}
