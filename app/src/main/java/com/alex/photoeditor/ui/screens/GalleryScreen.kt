package com.alex.photoeditor.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.ImageView
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alex.photoeditor.core.MediaItem
import com.alex.photoeditor.core.MediaKind
import com.alex.photoeditor.core.MediaStoreRepository
import com.alex.photoeditor.core.FolderMaker
import com.alex.photoeditor.ui.GalleryTab
import com.alex.photoeditor.ui.GalleryViewModel
import com.alex.photoeditor.ui.components.MediaViewer
import com.alex.photoeditor.ui.components.formatDuration
import com.alex.photoeditor.ui.components.NamedActionTonalIconButton
import com.alex.photoeditor.ui.components.NamedActionIconButton
import com.alex.photoeditor.ui.components.formatSize
import com.alex.photoeditor.ui.components.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the device already has: photos, videos, audio, documents and APKs.
 *
 * Five inner tabs, swipeable in either direction — the pager is the source
 * of truth and the tab pills just follow it. Documents and APKs are listed
 * flat from MediaStore.Files (every storage volume); the three media tabs are
 * folder-first: every folder (strictly grouped by
 * [MediaStore.MediaColumns.BUCKET_ID], so same-named folders stay separate),
 * sortable Recent / Alphabetical / Size / Custom. Media outside any folder
 * appears under the phone's model name. Folders can be created (as real
 * directories on external storage), moved and copied between by long-press
 * selection, and deleted to the system Trash.
 *
 * Long-press any item to select it; the selection bar carries Move (cut),
 * Copy, and Delete (to Trash).
 *
 * Tapping a photo hands it to the editor; tapping anything else plays it in
 * place, because a gallery is a viewing surface first. Documents and APKs
 * open in whichever app handles them (the package installer for APKs).
 */

/** How folders are ordered. */
private enum class FolderSort(val label: String) {
    RECENT("Recent"),
    ALPHA("Alphabetical"),
    SIZE("Largest first"),
    CUSTOM("Custom order")
}

/** Whether the pending destination-dialog action moves or copies. */
private enum class MediaAction(val verb: String, val dialogTitle: String, val confirmLabel: String) {
    MOVE("Move", "Move", "Move here"),
    COPY("Copy", "Copy", "Copy here")
}

/**
 * A pending Trash operation. [caption] is only used for the confirmation
 * dialog's wording ("files" / "folders"); [userFoldersToForget] are
 * app-bookkept folder names to forget after a successful trash.
 */
private data class TrashPlan(
    val caption: String,
    val items: List<MediaItem>,
    val userFoldersToForget: List<String> = emptyList()
)

private const val PREFS = "gallery_prefs"
private const val PREF_SORT = "folder_sort"
private const val PREF_ORDER = "folder_order"

private fun readFolderSort(context: Context): FolderSort =
    runCatching { FolderSort.valueOf(prefs(context).getString(PREF_SORT, FolderSort.RECENT.name)!!) }
        .getOrDefault(FolderSort.RECENT)

private fun writeFolderSort(context: Context, sort: FolderSort) {
    prefs(context).edit().putString(PREF_SORT, sort.name).apply()
}

private fun readFolderOrder(context: Context): List<String> =
    prefs(context).getString(PREF_ORDER, null)
        ?.split('\n')
        ?.filter { it.isNotBlank() }
        .orEmpty()

private fun writeFolderOrder(context: Context, order: List<String>) {
    prefs(context).edit().putString(PREF_ORDER, order.joinToString("\n")).apply()
}

private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
@Composable
fun GalleryScreen(
    onEditPhoto: (Uri) -> Unit,
    resumeViewerUri: Uri? = null,
    onViewerResumeConsumed: () -> Unit = {}
) {
    // Storage is gated for the whole app by AllFilesGate in MainActivity;
    // reaching this composable means access is already granted, and MediaStore
    // queries / file ops all just work.
    val context = LocalContext.current
    val vm: GalleryViewModel = viewModel()

    // Sort order for folders. Recent is the default; Custom is the order the
    // user arranges by hand, persisted across launches.
    var folderSort by remember { mutableStateOf(readFolderSort(context)) }
    var customOrder by remember { mutableStateOf(readFolderOrder(context)) }

    fun setSort(sort: FolderSort) {
        folderSort = sort
        writeFolderSort(context, sort)
    }

    // (bucketKey, displayName) of the folder being viewed. Lives in the VM so
    // returning from the editor lands on the exact folder, not just the tab.
    val openFolder = vm.openFolderKey

    // Selection (long-press) state. Exits whenever the tab or the folder
    // changes, so a stale selection cannot leak across screens.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }

    // Folder selection (long-press on a folder), the same idea aimed at
    // folders. Carries Delete and Info actions; mutually exclusive with item
    // selection.
    var folderSelectionMode by remember { mutableStateOf(false) }
    var selectedFolderKeys by remember { mutableStateOf(emptySet<String>()) }

    /** Non-null query means the unified search view replaces the tabs. */
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<MediaItem?>(null) }
    var renameFolderTarget by remember { mutableStateOf<FolderGroup?>(null) }
    BackHandler(enabled = searchQuery != null) { searchQuery = null }
    LaunchedEffect(searchQuery) {
        if (searchQuery != null) {
            selectionMode = false
            selectedIds = emptySet()
            folderSelectionMode = false
            selectedFolderKeys = emptySet()
        }
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun exitFolderSelection() {
        folderSelectionMode = false
        selectedFolderKeys = emptySet()
    }

    LaunchedEffect(vm.tab) {
        exitSelection()
        exitFolderSelection()
    }
    LaunchedEffect(openFolder) {
        exitSelection()
        exitFolderSelection()
    }

    fun visibleItems(): List<MediaItem> {
        val base = vm.itemsOf(vm.tab)
        val folder = openFolder ?: return base
        return base.filter { folderKey(it) == folder.first }
    }

    fun selectedItems(): List<MediaItem> =
        visibleItems().filter { it.id in selectedIds }

    fun toggleSelect(item: MediaItem) {
        selectionMode = true
        selectedIds = if (item.id in selectedIds) {
            selectedIds - item.id
        } else {
            selectedIds + item.id
        }
    }

    fun toggleFolderSelect(key: String) {
        folderSelectionMode = true
        selectedFolderKeys = if (key in selectedFolderKeys) {
            selectedFolderKeys - key
        } else {
            selectedFolderKeys + key
        }
    }

    // Snapshot the list when the viewer opens: a background refresh must not
    // shrink the list under the pager while the viewer is mid-swipe.
    var viewerItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var viewerStart by remember { mutableStateOf(0) }

    /**
     * After the editor closes and returns here ("Edit → Back"), re-open the
     * viewer on the exact image that was being edited — not just at the tab
     * or folder grid level. Keyed ONLY on resumeViewerUri so it fires exactly
     * once and refresh() can never re-trigger it.
     */
    LaunchedEffect(resumeViewerUri) {
        if (resumeViewerUri == null) return@LaunchedEffect
        val candidates = visibleItems()
        val index = candidates.indexOfFirst { it.uri == resumeViewerUri }
        if (index >= 0) {
            viewerItems = candidates
            viewerStart = index
        }
        onViewerResumeConsumed()
    }

    fun openViewer(items: List<MediaItem>, index: Int) {
        if (items.isEmpty()) return
        viewerItems = items
        viewerStart = index.coerceIn(0, items.size - 1)
    }

    // One-shot message pill, for move results and creation confirmations.
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(3200)
            message = null
        }
    }

    /** Confirmation pending for a trash request. */
    var confirmTrash by remember { mutableStateOf<TrashPlan?>(null) }
    /** Folder shown in the info dialog when exactly one folder is selected. */
    var showFolderInfo by remember { mutableStateOf<FolderGroup?>(null) }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    /** Non-null while the move/copy destination picker is up. */
    var pendingAction by remember { mutableStateOf<MediaAction?>(null) }
    // When the user creates a folder from inside the move/copy dialog, the
    // dialog reopens with the same action rather than dropping the operation.
    var reopenActionAfterFolder by remember { mutableStateOf<MediaAction?>(null) }
    var pendingMove by remember { mutableStateOf<Pair<List<MediaItem>, String>?>(null) }

    val scope = rememberCoroutineScope()

    val moveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingMove ?: return@rememberLauncherForActivityResult
        pendingMove = null
        val (items, folder) = pending
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                var moved = 0
                withContext(Dispatchers.IO) {
                    items.forEach { item ->
                        val values = ContentValues().apply {
                            // One home for all media: DCIM/<name>/ — a public,
                            // real directory MediaStore creates on demand.
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$folder/")
                        }
                        moved += runCatching {
                            context.contentResolver.update(item.uri, values, null, null)
                        }.getOrDefault(0)
                    }
                }
                message = if (moved == items.size) {
                    "Moved $moved ${if (moved == 1) "item" else "items"} to $folder"
                } else {
                    "Moved $moved of ${items.size} items to $folder"
                }
                exitSelection()
                vm.removeItemsLocally(vm.tab, items.map { it.id }.toSet())
            }
        } else {
            message = "Nothing was moved"
        }
    }

    fun requestMove(items: List<MediaItem>, folder: String) {
        if (items.isEmpty()) return
        // With all-files access the move is a plain filesystem rename plus
        // a rescan — no system consent dialog needed, works everywhere.
        if (FolderMaker.hasAllFilesAccess(context)) {
            scope.launch {
                var moved = 0
                withContext(Dispatchers.IO) {
                    moved = FolderMaker.moveAll(context, items, folder)
                }
                message = if (moved == items.size) {
                    "Moved $moved ${if (moved == 1) "item" else "items"} to $folder"
                } else {
                    "Moved $moved of ${items.size} items to $folder"
                }
                exitSelection()
                vm.removeItemsLocally(vm.tab, items.map { it.id }.toSet())
            }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            message = "Moving files needs Android 11 or newer"
            return
        }
        pendingMove = items to folder
        val sender = MediaStore.createWriteRequest(
            context.contentResolver,
            items.map { it.uri }
        )
        moveLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    fun requestTrash(caption: String, items: List<MediaItem>, userFoldersToForget: List<String> = emptyList()) {
        if (items.isEmpty()) return
        scope.launch {
            val moved = com.alex.photoeditor.core.TrashStore.importItems(context, items)
            if (moved == 0) {
                message = "Could not move — the files may be read-only or in use"
                return@launch
            }
            if (caption == "folders") {
                userFoldersToForget.forEach { folderName ->
                    vm.removeUserFolder(folderName)
                    // The media is gone to the bin now; if the directory is
                    // empty we created, remove it too — otherwise it lingers
                    // as a ghost empty folder.
                    val root = com.alex.photoeditor.core.FolderMaker.sharedStorageRoot()
                    val primary = java.io.File(root, folderName)
                    val dcim = java.io.File(root, android.os.Environment.DIRECTORY_DCIM)
                    val legacy = java.io.File(dcim, folderName)
                    primary.listFiles()?.isEmpty().let { if (primary.exists() && it == true) primary.delete() }
                    legacy.listFiles()?.isEmpty().let { if (legacy.exists() && it == true) legacy.delete() }
                }
            }
            // Remove the files from the visible list immediately; the full
            // library query runs quietly afterwards.
            vm.removeItemsLocally(vm.tab, items.map { it.id }.toSet())
            vm.refreshTrashLocal()
            exitSelection()
            exitFolderSelection()
            // Stay on the screen the user is on: the folder they were viewing,
            // the selection bar, the viewer — do not jump back to the folder grid.
            message = if (caption == "folders") "Folder deleted ($moved)" else "Moved to Trash ($moved)"
        }
    }
    /**
     * Copies each item into the destination folder, keeping the originals.
     *
     * Creates new files it owns, so (unlike moving) no write grant dialog is
     * needed from Android 10 up; below that there is no clean route.
     */
    fun requestCopy(items: List<MediaItem>, folder: String) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            message = "Copying needs Android 10 or newer"
            return
        }
        scope.launch {
            val copiedPaths = ArrayList<Pair<MediaItem, String>>()
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    copyIntoFolder(context, item, folder)?.let { copiedPaths.add(item to it) }
                }
            }
            copiedPaths.forEach { (item, path) -> vm.addCopiedItem(item, path) }
            val copied = copiedPaths.size
            message = if (copied == items.size) {
                "Copied $copied ${if (copied == 1) "item" else "items"} to $folder"
            } else {
                "Copied $copied of ${items.size} items to $folder"
            }
            exitSelection()
        }
    }

    // Inside a folder drill-in, Back goes up to the folder grid — not out
    // of the gallery entirely. The viewer registers its own BackHandler and
    // wins over this one while it is open.
    BackHandler(enabled = openFolder != null && !selectionMode && !folderSelectionMode) { vm.closeFolder() }
    BackHandler(enabled = selectionMode) { exitSelection() }
    BackHandler(enabled = folderSelectionMode) { exitFolderSelection() }

    val photosItems = vm.itemsOf(GalleryTab.PHOTOS)
    val videosItems = vm.itemsOf(GalleryTab.VIDEOS)
    val audioItems = vm.itemsOf(GalleryTab.AUDIO)
    val docsItems = vm.itemsOf(GalleryTab.DOCUMENTS)
    val apkItems = vm.itemsOf(GalleryTab.APK)

    // All five lists must be keys: documents/APKs load asynchronously, and a
    // memo keyed only on media would keep showing the empty document folders.
    val destinations = remember(
        photosItems, videosItems, audioItems, docsItems, apkItems, vm.userFolders
    ) {
        val existing = (photosItems + videosItems + audioItems + docsItems + apkItems)
            .mapNotNull { it.bucket?.takeIf { name -> name.isNotBlank() } }
        (existing + vm.userFolders)
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    // Computed once per (data, sort) change so the pager, the folders panel
    // and the top bar's folder-selection mode all read the same grouping
    // without re-sorting on every frame.
    val foldersByTab = remember(
        photosItems, videosItems, audioItems, docsItems, apkItems,
        vm.userFolders, folderSort, customOrder
    ) {
        GalleryTab.entries.associateWith { tab ->
            sortFolders(groupByFolder(vm.itemsOf(tab), vm.userFolders), folderSort, customOrder)
        }
    }
    val currentFolders = foldersByTab.getValue(vm.tab)
    val selectedFolderGroups = currentFolders.filter { it.key.toString() in selectedFolderKeys }

    // Search spans every category at once — the five tab lists are merged
    // here, newest first, then filtered by the query in the results view.
    val allItemsForSearch = GalleryTab.entries.flatMap { vm.itemsOf(it) }

    // Top and side insets are applied by the host screen; the bottom inset
    // is owned by the app's bottom navigation bar.
    if (vm.showTrash) {
        TrashScreen(vm)
    } else {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (folderSelectionMode) {
            FolderSelectionBar(
                count = selectedFolderKeys.size,
                allSelected = selectedFolderKeys.containsAll(currentFolders.map { it.key.toString() }.toSet()),
                onAllNone = {
                    val all = currentFolders.map { it.key.toString() }.toSet()
                    selectedFolderKeys = if (selectedFolderKeys.containsAll(all)) {
                        emptySet()
                    } else {
                        all
                    }
                },
                onInfo = { showFolderInfo = selectedFolderGroups.firstOrNull() },
                infoEnabled = selectedFolderGroups.size == 1,
                onRename = { renameFolderTarget = selectedFolderGroups.firstOrNull() },
                renameEnabled = selectedFolderGroups.size == 1,
                onDelete = {
                    val groups = selectedFolderGroups
                    val items = groups.flatMap { it.items }
                    val forgets = groups.filter { it.userCreated }.map { it.name }
                    confirmTrash = TrashPlan(
                        caption = "folders",
                        items = items,
                        userFoldersToForget = forgets
                    )
                },
                onExit = ::exitFolderSelection
            )
        } else if (selectionMode) {
            val visible = visibleItems().map { it.id }.toSet()
            SelectionBar(
                count = selectedIds.size,
                allSelected = selectedIds.containsAll(visible),
                onSelectAll = {
                    selectedIds = if (selectedIds.containsAll(visible)) selectedIds - visible else selectedIds + visible
                },
                onMove = { pendingAction = MediaAction.MOVE },
                onCopy = { pendingAction = MediaAction.COPY },
                onDelete = {
                    val items = selectedItems()
                    confirmTrash = TrashPlan(caption = "files", items = items)
                },
                onRename = { renameTarget = selectedItems().firstOrNull() },
                renameEnabled = selectedIds.size == 1,
                onExit = ::exitSelection
            )
        } else {
            GalleryTopBar(
                onRefresh = vm::refresh,
                isLoading = vm.isLoading,
                sort = folderSort,
                onSortChange = ::setSort,
                onCreateFolder = { showNewFolderDialog = true },
                onOpenTrash = vm::openTrash,
                onSearch = { searchQuery = "" }
            )
        }

        if (searchQuery == null) {
            TabRow(selected = vm.tab, onSelect = vm::selectTab)
        } else {
            SearchBar(
                query = searchQuery ?: "",
                onQueryChange = { searchQuery = it },
                onClose = { searchQuery = null }
            )
        }

        if (vm.isLoading) {
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                searchQuery != null -> {
                    val query = searchQuery.orEmpty().trim()
                    val results = if (query.isBlank()) {
                        emptyList()
                    } else {
                        allItemsForSearch.filter { item ->
                            item.name.contains(query, ignoreCase = true) ||
                                item.relativePath?.contains(query, ignoreCase = true) == true ||
                                item.mimeType.contains(query, ignoreCase = true)
                        }.sortedByDescending { it.dateAdded }
                    }
                    when {
                        query.isBlank() -> SearchHint()
                        results.isEmpty() -> SearchEmpty(query)
                        else -> SearchResults(
                            items = results,
                            onItemClick = { item, index ->
                                when (item.kind) {
                                    MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO ->
                                        openViewer(results, index)
                                    else -> openFile(context, item)
                                }
                            }
                        )
                    }
                }

                openFolder != null -> {
                    val folder = openFolder!!
                    val folderItems = visibleItems()
                    Column(modifier = Modifier.fillMaxSize()) {
                        FolderHeader(
                            name = folder.second,
                            count = folderItems.size,
                            onBack = { vm.closeFolder() }
                        )
                        if (folderItems.isEmpty()) {
                            EmptyFolderContent("This folder is empty.")
                        } else {
                            when (vm.tab) {
                                GalleryTab.AUDIO -> AudioList(
                                    items = folderItems,
                                    selectionMode = selectionMode,
                                    selectedIds = selectedIds,
                                    onItemClick = { item, index ->
                                        if (selectionMode) toggleSelect(item)
                                        else openViewer(folderItems, index)
                                    },
                                    onToggleSelect = ::toggleSelect
                                )
                                GalleryTab.DOCUMENTS, GalleryTab.APK -> FileList(
                                    items = folderItems,
                                    selectionMode = selectionMode,
                                    selectedIds = selectedIds,
                                    onItemClick = { openFile(context, it) },
                                    onToggleSelect = ::toggleSelect
                                )
                                else -> MediaGrid(
                                    items = folderItems,
                                    selectionMode = selectionMode,
                                    selectedIds = selectedIds,
                                    onItemClick = { item, index ->
                                        if (selectionMode) toggleSelect(item)
                                        else openViewer(folderItems, index)
                                    },
                                    onToggleSelect = ::toggleSelect
                                )
                            }
                        }
                    }
                }

                else -> {
                    val swipeThreshold = LocalDensity.current.density * 48f
                    AnimatedContent(
                        targetState = vm.tab,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "galleryTab",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(vm.tab, openFolder) {
                                // Swiping changes tabs left/right; drill-in has its
                                // own back-able state, so swipe is disabled there.
                                if (openFolder != null) return@pointerInput
                                var drag = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { drag = 0f },
                                    onHorizontalDrag = { change, amount ->
                                        drag += amount
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        val entries = GalleryTab.entries
                                        when {
                                            drag < -swipeThreshold -> {
                                                val next = (vm.tab.ordinal + 1) % entries.size
                                                vm.selectTab(entries[next])
                                            }
                                            drag > swipeThreshold -> {
                                                val prev = (vm.tab.ordinal - 1 + entries.size) % entries.size
                                                vm.selectTab(entries[prev])
                                            }
                                        }
                                    }
                                )
                            }
                    ) { pageTab ->
                    val items = vm.itemsOf(pageTab)
                    val error = vm.errorOf(pageTab)
                    val loading = vm.isLoadingOf(pageTab)

                    val isFlatFileTab = pageTab == GalleryTab.APK

                    when {
                        error != null -> ErrorState(
                            message = error,
                            onRetry = vm::refresh
                        )

                        items.isEmpty() && !loading &&
                            (isFlatFileTab || vm.userFolders.isEmpty()) -> EmptyTab(pageTab)

                        isFlatFileTab -> FileList(
                            items = items,
                            selectionMode = selectionMode,
                            selectedIds = selectedIds,
                            onItemClick = { openFile(context, it) },
                            onToggleSelect = ::toggleSelect
                        )

                        else -> FoldersPanel(
                            folders = foldersByTab.getValue(pageTab),
                            selectionMode = folderSelectionMode,
                            selectedKeys = selectedFolderKeys,
                            custom = folderSort == FolderSort.CUSTOM,
                            onToggleSelect = { group -> toggleFolderSelect(group.key.toString()) },
                            onCustomOrderChanged = { order ->
                                customOrder = order
                                writeFolderOrder(context, order)
                            },
                            onOpenFolder = { group -> vm.openFolder(group.key.toString(), group.name) }
                        )
                    }
                    }
                }
            }

            viewerItems?.let { items ->
                MediaViewer(
                    items = items,
                    initialIndex = viewerStart,
                    onDismiss = { viewerItems = null },
                    onEditPhoto = { uri ->
                        viewerItems = null
                        onEditPhoto(uri)
                    }
                )
            }

            message?.let { text ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 24.dp, end = 24.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onCreate = { name ->
                if (vm.addUserFolder(name)) {
                    // Root of shared storage when all-files access is granted,
                    // DCIM via the MediaStore marker trick otherwise.
                    scope.launch {
                        val outcome = FolderMaker.create(context, name)
                        withContext(Dispatchers.Main) {
                            message = when (outcome) {
                                FolderMaker.Outcome.RootCreated ->
                                    "Folder \"$name\" created on storage"
                                FolderMaker.Outcome.DcimFallback ->
                                    "Folder \"$name\" created in DCIM — enable All files access in app info for storage root"
                                FolderMaker.Outcome.Failed ->
                                    "Folder exists in the gallery only — storage would not create the real directory"
                            }
                        }
                    }
                    reopenActionAfterFolder?.let { action ->
                        pendingAction = action
                        reopenActionAfterFolder = null
                    }
                } else {
                    message = "That folder name is not usable"
                }
                showNewFolderDialog = false
            }
        )
    }

    showFolderInfo?.let { folder ->
        FolderInfoDialog(folder = folder, onClose = { showFolderInfo = null })
    }

    renameTarget?.let { target ->
        RenameDialog(
            title = "Rename file",
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        renameMediaItem(context, target, newName)
                    }
                    if (ok) {
                        vm.renameItemLocally(vm.tab, target.id, newName)
                        message = "Renamed to $newName"
                    } else {
                        message = "Could not rename — the name may be in use"
                    }
                    exitSelection()
                }
            }
        )
    }

    renameFolderTarget?.let { target ->
        RenameDialog(
            title = "Rename folder",
            initial = target.name,
            onDismiss = { renameFolderTarget = null },
            onConfirm = { newName ->
                renameFolderTarget = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        renameFolderOnDisk(context, target, newName)
                    }
                    if (ok) {
                        if (target.userCreated) {
                            com.alex.photoeditor.core.UserFolders.rename(context, target.name, newName)
                            vm.removeUserFolder(target.name)
                            vm.addUserFolder(newName)
                        }
                        message = "Folder renamed to $newName"
                    } else {
                        message = "Could not rename the folder — the name may be in use"
                    }
                    exitFolderSelection()
                    vm.closeFolder()
                }
            }
        )
    }

    if (pendingAction != null) {
        MoveCopyDialog(
            action = pendingAction!!,
            destinations = destinations,
            selectionCount = selectedIds.size,
            onDismiss = { pendingAction = null },
            onNewFolder = {
                reopenActionAfterFolder = pendingAction
                pendingAction = null
                showNewFolderDialog = true
            },
            onConfirm = { folder ->
                val action = pendingAction!!
                pendingAction = null
                if (action == MediaAction.COPY) {
                    requestCopy(selectedItems(), folder)
                } else {
                    requestMove(selectedItems(), folder)
                }
            }
        )
    }

    confirmTrash?.let { plan ->
        val isFolderPlan = plan.caption == "folders"
        val isEmptyFolder = isFolderPlan && plan.items.isEmpty()
        AlertDialog(
            onDismissRequest = { confirmTrash = null },
            title = { Text(if (isEmptyFolder) "Remove folders?" else if (isFolderPlan) "Delete folders?" else "Move to Trash?") },
            text = {
                Text(
                    when {
                        isEmptyFolder ->
                            "The empty folder will be removed."
                        isFolderPlan ->
                            "Everything inside (${plan.items.size} ${if (plan.items.size == 1) "item" else "items"}) will move to the system Trash and can be restored there for 30 days."
                        else ->
                            "${plan.items.size} ${if (plan.items.size == 1) "item" else "items"} will move to the system Trash. They can be recovered there for 30 days."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTrash = null
                        if (isEmptyFolder) {
                            plan.userFoldersToForget.forEach { vm.removeUserFolder(it) }
                            exitFolderSelection()
                            message = "Folder removed"
                        } else {
                            requestTrash(plan.caption, plan.items, plan.userFoldersToForget)
                        }
                    }
                ) {
                    Text(
                        when {
                            isEmptyFolder -> "Remove"
                            isFolderPlan -> "Delete folders"
                            else -> "Move to Trash"
                        },
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrash = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// -------------------------------------------------------------------- top bar

@Composable
private fun GalleryTopBar(
    onRefresh: () -> Unit,
    isLoading: Boolean,
    sort: FolderSort,
    onSortChange: (FolderSort) -> Unit,
    onCreateFolder: () -> Unit,
    onOpenTrash: () -> Unit,
    onSearch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Spacer(Modifier.weight(1f))
        NamedActionTonalIconButton(
            name = "Search all files",
            icon = Icons.Filled.Search,
            onClick = onSearch
        )
        SortMenu(sort = sort, onSortChange = onSortChange)
        NamedActionTonalIconButton(
            name = "Trash",
            icon = Icons.Filled.Delete,
            onClick = onOpenTrash
        )
        NamedActionTonalIconButton(
            name = "New folder",
            icon = Icons.Filled.CreateNewFolder,
            onClick = onCreateFolder
        )
        NamedActionTonalIconButton(
            name = "Refresh",
            icon = Icons.Filled.Refresh,
            onClick = onRefresh,
            enabled = !isLoading
        )
    }
}

@Composable
private fun SortMenu(
    sort: FolderSort,
    onSortChange: (FolderSort) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        NamedActionTonalIconButton(
            name = "Sort folders",
            icon = Icons.Filled.Sort,
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            FolderSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        if (sort == option) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    onClick = {
                        onSortChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Top action bar while items are selected. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    renameEnabled: Boolean,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NamedActionIconButton(
            name = "Clear selection",
            icon = Icons.Filled.Close,
            onClick = onExit
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.weight(1f))
        CircleCheckToggle(
            checked = allSelected,
            onClick = onSelectAll,
            name = "Select all / none"
        )
        NamedActionIconButton(
            name = "Move",
            icon = Icons.Filled.ContentCut,
            onClick = onMove,
            enabled = count > 0
        )
        NamedActionIconButton(
            name = "Copy",
            icon = Icons.Filled.ContentCopy,
            onClick = onCopy,
            enabled = count > 0
        )
        NamedActionIconButton(
            name = "Rename",
            icon = Icons.Filled.Edit,
            onClick = onRename,
            enabled = renameEnabled
        )
        NamedActionIconButton(
            name = "Delete",
            icon = Icons.Filled.Delete,
            onClick = onDelete,
            enabled = count > 0,
            tint = MaterialTheme.colorScheme.error
        )
    }
}

/** Top action bar while folders are selected (long-press). */
@Composable
private fun FolderSelectionBar(
    count: Int,
    allSelected: Boolean,
    onAllNone: () -> Unit,
    onInfo: () -> Unit,
    infoEnabled: Boolean,
    onRename: () -> Unit,
    renameEnabled: Boolean,
    onDelete: () -> Unit,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NamedActionIconButton(
            name = "Clear selection",
            icon = Icons.Filled.Close,
            onClick = onExit
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.weight(1f))
        CircleCheckToggle(
            checked = allSelected,
            onClick = onAllNone,
            name = "Select all / none"
        )
        NamedActionIconButton(
            name = "Folder info",
            icon = Icons.Filled.Info,
            onClick = onInfo,
            enabled = infoEnabled
        )
        NamedActionIconButton(
            name = "Rename folder",
            icon = Icons.Filled.Edit,
            onClick = onRename,
            enabled = renameEnabled
        )
        NamedActionIconButton(
            name = "Delete folder",
            icon = Icons.Filled.Delete,
            onClick = onDelete,
            enabled = count > 0,
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun TabRow(
    selected: GalleryTab,
    onSelect: (GalleryTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GalleryTab.entries.forEach { tab ->
            TabPill(
                label = tab.label,
                icon = when (tab) {
                    GalleryTab.PHOTOS -> Icons.Filled.PhotoLibrary
                    GalleryTab.VIDEOS -> Icons.Filled.Movie
                    GalleryTab.AUDIO -> Icons.Filled.MusicNote
                    GalleryTab.DOCUMENTS -> Icons.Filled.Description
                    GalleryTab.APK -> Icons.Filled.GetApp
                },
                selected = selected == tab,
                onClick = { onSelect(tab) },
                modifier = Modifier.widthIn(min = 104.dp)
            )
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** Recent vs Folders. One toggle per tab, remembered across tabs. */

// ------------------------------------------------------------------ grids

@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onItemClick: (MediaItem, Int) -> Unit,
    onToggleSelect: (MediaItem) -> Unit
) {
    val gridState = rememberLazyGridState()
    // The grid scope in this Compose version has no items(count) overload,
    // so index the list once per change instead of searching per tile.
    val indexByUri = remember(items) {
        items.withIndex().associate { (index, item) -> item.uri to index }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        gridItems(items, key = { it.uri }) { item ->
            val index = indexByUri[item.uri] ?: 0
            MediaTile(
                item = item,
                selectionMode = selectionMode,
                selected = item.id in selectedIds,
                onClick = { onItemClick(item, index) },
                onToggleSelect = { onToggleSelect(item) }
            )
        }
    }
}

@Composable
private fun MediaTile(
    item: MediaItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    val thumbnail = rememberThumbnail(item, 256)
    val animatedGif = rememberAnimatedGif(item)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onClick() },
                onLongClick = onToggleSelect
            )
            .semantics {
                contentDescription = when (item.kind) {
                    MediaKind.VIDEO -> "Video ${item.name}, ${item.durationMs?.let(::formatDuration) ?: ""}"
                    MediaKind.DOCUMENT -> "Document ${item.name}"
                    MediaKind.APK -> "APK ${item.name}"
                    else -> "Photo ${item.name}"
                }
            }
    ) {
        val gif = animatedGif
        if (gif != null) {
            AnimatedGifImage(gif, modifier = Modifier.fillMaxSize())
        } else if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (item.kind == MediaKind.VIDEO) Icons.Filled.Movie else Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        if (item.kind == MediaKind.VIDEO) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    item.durationMs?.let(::formatDuration) ?: "Video",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }

        if (selectionMode) {
            SelectionBadge(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            )
        }
    }
}

@Composable
private fun SelectionBadge(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/**
 * Circular toggle that matches [SelectionBadge]: filled + tick when on,
 * hollow circle when off. Used for "Select all / none".
 */
@Composable
private fun CircleCheckToggle(
    checked: Boolean,
    onClick: () -> Unit,
    name: String
) {
    val background = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// --------------------------------------------------------------------- search

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NamedActionIconButton(
            name = "Close search",
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onClose
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search files by name") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            } else null,
            modifier = Modifier.weight(1f).focusRequester(focusRequester)
        )
    }
}

@Composable
private fun SearchHint() {
    Column(
        modifier = Modifier.fillMaxSize().padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Search all your files",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Photos, videos, audio, documents and APKs — one result list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SearchEmpty(query: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "No files found for “$query”",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SearchResults(
    items: List<MediaItem>,
    onItemClick: (MediaItem, Int) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(items, key = { it.uri }) { item ->
            val index = items.indexOf(item)
            SearchResultRow(
                item = item,
                onClick = { onItemClick(item, index) }
            )
        }
    }
}

@Composable
private fun SearchResultRow(item: MediaItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val mediaThumb = rememberThumbnail(item, 128)
    val appIcon by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = if (item.kind == MediaKind.APK) {
            withContext(Dispatchers.IO) {
                loadApkIconFor(
                    context,
                    name = item.name,
                    mimeType = item.mimeType,
                    sizeBytes = item.sizeBytes,
                    uri = item.uri,
                    storedPath = MediaStoreRepository.filePathFor(context, item)
                )
            }
        } else null
    }
    val docThumb by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) {
            MediaStoreRepository.loadDocumentThumbnail(context, item, 128)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            val thumb = when (item.kind) {
                MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO -> mediaThumb
                else -> appIcon ?: docThumb
            }
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    when (item.kind) {
                        MediaKind.VIDEO -> Icons.Filled.Movie
                        MediaKind.AUDIO -> Icons.Filled.MusicNote
                        MediaKind.DOCUMENT -> Icons.Filled.Description
                        MediaKind.APK -> Icons.Filled.GetApp
                        else -> Icons.Filled.Image
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(
                        when (item.kind) {
                            MediaKind.IMAGE -> "Photo"
                            MediaKind.VIDEO -> "Video"
                            MediaKind.AUDIO -> "Audio"
                            MediaKind.DOCUMENT -> "Document"
                            MediaKind.APK -> "APK"
                        }
                    )
                    append(" · ")
                    append(formatSize(item.sizeBytes))
                    item.relativePath?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------- documents / APKs

/** Opens a file: APKs go straight to the package installer, others to a chooser. */
private fun openFile(context: Context, item: MediaItem) {
    if (item.kind == MediaKind.APK ||
        item.name.endsWith(".apk", ignoreCase = true) ||
        item.mimeType == "application/vnd.android.package-archive"
    ) {
        installApk(context, item)
        return
    }
    val mime = item.mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: com.alex.photoeditor.core.SystemTrash.mimeTypeForName(item.name)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, "file", item.uri)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
        .onFailure {
            android.widget.Toast.makeText(
                context,
                "No app found to open this file.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
}

private const val APK_MIME = "application/vnd.android.package-archive"

private fun apkViewIntent(dataUri: Uri, context: Context): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(dataUri, APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        clipData = ClipData.newUri(context.contentResolver, "apk", dataUri)
    }

/**
 * On Android 8+ the caller must both declare REQUEST_INSTALL_PACKAGES and be
 * granted "Install unknown apps" by the user. Without the declaration the
 * installer resolves but silently does nothing — hence the explicit check
 * here that takes the user to the setting with a clear explanation.
 */
private fun installApk(context: Context, item: MediaItem) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
        android.widget.Toast.makeText(
            context,
            "Allow Edit by Alex to install apps, then tap the APK again.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        return
    }
    runCatching { context.startActivity(apkViewIntent(item.uri, context)) }
        .onFailure {
            // Some providers won't grant the installer read access to their
            // content URI. Copy into our own FileProvider space and retry.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                installApkFromCopy(context, item)
            }
        }
}

private suspend fun installApkFromCopy(context: Context, item: MediaItem) =
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val safeName = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val copy = File(dir, safeName)
        val copied = runCatching {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                copy.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } != null && copy.length() > 0
        }.getOrDefault(false)
        if (!copied) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    "Could not read this APK to install it.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return@withContext
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            copy
        )
        withContext(Dispatchers.Main) {
            runCatching { context.startActivity(apkViewIntent(uri, context)) }
                .onFailure {
                    android.widget.Toast.makeText(
                        context,
                        "No app can install APKs on this device.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

@Composable
private fun FileList(
    items: List<MediaItem>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onItemClick: (MediaItem) -> Unit,
    onToggleSelect: (MediaItem) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(items, key = { it.uri }) { item ->
            FileRow(
                item = item,
                selectionMode = selectionMode,
                selected = item.id in selectedIds,
                onClick = { if (selectionMode) onToggleSelect(item) else onItemClick(item) },
                onToggleSelect = { onToggleSelect(item) }
            )
        }
    }
}

@Composable
private fun FileRow(
    item: MediaItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    val context = LocalContext.current
    val appIcon by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = if (item.kind == MediaKind.APK) {
            withContext(Dispatchers.IO) {
                loadApkIconFor(
                    context,
                    name = item.name,
                    mimeType = item.mimeType,
                    sizeBytes = item.sizeBytes,
                    uri = item.uri,
                    storedPath = MediaStoreRepository.filePathFor(context, item)
                )
            }
        } else null
    }
    val docThumb by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) {
            MediaStoreRepository.loadDocumentThumbnail(context, item, 128)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onToggleSelect
            )
            .semantics {
                contentDescription = when (item.kind) {
                    MediaKind.APK -> "APK ${item.name}"
                    else -> "Document ${item.name}"
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            val icon = appIcon
            val docBitmap = docThumb
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(3.dp)
                )
            } else if (docBitmap != null) {
                Image(
                    bitmap = docBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    if (item.kind == MediaKind.APK) Icons.Filled.GetApp else Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                buildString {
                    append(formatSize(item.sizeBytes))
                    append(" · ")
                    append(formatDate(item.dateAdded))
                    item.relativePath?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selectionMode) {
            Spacer(Modifier.width(8.dp))
            SelectionBadge(selected = selected)
        }
    }
}

@Composable
private fun AudioList(
    items: List<MediaItem>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onItemClick: (MediaItem, Int) -> Unit,
    onToggleSelect: (MediaItem) -> Unit
) {
    val indexByUri = remember(items) {
        items.withIndex().associate { (index, item) -> item.uri to index }
    }

    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(items, key = { it.uri }) { item ->
            val index = indexByUri[item.uri] ?: 0
            AudioRow(
                item = item,
                selectionMode = selectionMode,
                selected = item.id in selectedIds,
                onClick = { onItemClick(item, index) },
                onToggleSelect = { onToggleSelect(item) }
            )
        }
    }
}

@Composable
private fun AudioRow(
    item: MediaItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    val art = rememberThumbnail(item, 128)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onClick() },
                onLongClick = onToggleSelect
            )
            .semantics { contentDescription = "Audio ${item.displayName}" }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            if (selectionMode) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SelectionBadge(selected = selected)
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val artist = item.artist
                ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            Text(
                listOfNotNull(artist, item.durationMs?.let(::formatDuration)).joinToString(" · ")
                    .ifBlank { formatSize(item.sizeBytes) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!selectionMode) {
            Text(
                SimpleDateFormat("d MMM yy", Locale.getDefault()).format(Date(item.dateAdded * 1000)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ folders

/** Grouping key for the model-named bucket. Distinct from any real bucket id. */
private const val MODEL_BUCKET_KEY = "__model__"
private const val USER_BUCKET_KEY_PREFIX = "user:"

/** The folder a file lives in: bucket name, then its real path, then the model. */
private fun folderLabel(item: MediaItem): String =
    item.bucket?.takeIf { it.isNotBlank() }
        ?: item.relativePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: Build.MODEL.takeIf { it.isNotBlank() }
        ?: "Internal storage"

/**
 * Strict grouping key: bucket id first; files without one (e.g. documents in
 * the Files collection on some devices) group by their real relative path so
 * they still land in their own folders instead of one giant bucket.
 */
private fun folderKey(item: MediaItem): String =
    item.bucketId?.toString()
        ?: item.relativePath?.takeIf { it.isNotBlank() }?.let { "path:$it" }
        ?: MODEL_BUCKET_KEY

/** One folder (real or user-created) as a group of items. */
private class FolderGroup(
    val key: Any,
    val name: String,
    val items: List<MediaItem>,
    val userCreated: Boolean = false
) {
    val latest: Long get() = items.maxOfOrNull { it.dateAdded } ?: 0L
    val oldestAdded: Long? get() = items.minOfOrNull { it.dateAdded }
    val newestModified: Long? get() = items.maxOfOrNull { it.dateModified }
    val cover: MediaItem? get() = items.maxByOrNull { it.dateAdded }
    val bytes: Long get() = items.sumOf { it.sizeBytes }

    /**
     * Best-effort absolute address, e.g. /storage/emulated/0/DCIM/Camera/.
     *
     * MediaStore has no per-folder row, so the path of the first item stands
     * in for the whole bucket — every item in one bucket agrees on it. A
     * user-created empty folder has no items, so it falls back to the path
     * this app created it at.
     */
    val path: String?
        get() = items.firstOrNull()?.let { MediaStoreRepository.folderAddress(it.volumeName, it.relativePath) }
            ?: if (userCreated) "/storage/emulated/0/${Environment.DIRECTORY_DCIM}/$name/" else null
}

private fun groupByFolder(
    items: List<MediaItem>,
    userFolders: Set<String>
): List<FolderGroup> {
    val byBucket = items.groupBy(::folderKey)
        .map { (key, list) ->
            FolderGroup(key = key, name = folderLabel(list.first()), items = list)
        }
        .sortedByDescending { it.latest }

    // User-created folders with no media yet still show, so creating one is
    // not a dead end. Their key is prefixed so it can never collide with a
    // media bucket id.
    val existing = byBucket.mapTo(HashSet()) { it.name.lowercase(Locale.getDefault()) }
    val emptyUserFolders = userFolders
        .filterNot { it.lowercase(Locale.getDefault()) in existing }
        .map { name ->
            FolderGroup(
                key = "$USER_BUCKET_KEY_PREFIX$name",
                name = name,
                items = emptyList(),
                userCreated = true
            )
        }
        .sortedBy { it.name.lowercase(Locale.getDefault()) }

    return byBucket + emptyUserFolders
}

@Composable
private fun FoldersPanel(
    folders: List<FolderGroup>,
    selectionMode: Boolean,
    selectedKeys: Set<String>,
    custom: Boolean,
    onToggleSelect: (FolderGroup) -> Unit,
    onCustomOrderChanged: (List<String>) -> Unit,
    onOpenFolder: (FolderGroup) -> Unit
) {
    if (folders.isEmpty()) {
        EmptyFolderContent("No folders here yet.")
        return
    }

    // Custom order shows a reorderable list; the other sorts use the grid.
    if (custom) {
        // Free order: each folder moves anywhere with the arrows. Keys not
        // present in the persisted order land at the end (newest first).
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(folders.size, key = { folders[it].key.toString() }) { index ->
                val folder = folders[index]
                FolderOrderRow(
                    folder = folder,
                    selectionMode = selectionMode,
                    selected = folder.key.toString() in selectedKeys,
                    onToggleSelect = { onToggleSelect(folder) },
                    canMoveUp = index > 0,
                    canMoveDown = index < folders.size - 1,
                    onMoveUp = {
                        val order = folders.map { it.key.toString() }.toMutableList()
                        order.add(index - 1, order.removeAt(index))
                        onCustomOrderChanged(order)
                    },
                    onMoveDown = {
                        val order = folders.map { it.key.toString() }.toMutableList()
                        order.add(index + 1, order.removeAt(index))
                        onCustomOrderChanged(order)
                    },
                    onOpen = { onOpenFolder(folder) }
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        gridItems(folders, key = { it.key }) { folder ->
            FolderTile(
                folder = folder,
                selectionMode = selectionMode,
                selected = folder.key.toString() in selectedKeys,
                onClick = { onOpenFolder(folder) },
                onToggleSelect = { onToggleSelect(folder) }
            )
        }
    }
}

private fun sortFolders(
    folders: List<FolderGroup>,
    sort: FolderSort,
    customOrder: List<String>
): List<FolderGroup> = when (sort) {
    FolderSort.RECENT -> folders.sortedByDescending { it.latest }
    FolderSort.ALPHA -> folders.sortedBy { it.name.lowercase(Locale.getDefault()) }
    FolderSort.SIZE -> folders.sortedByDescending { it.bytes }
    FolderSort.CUSTOM -> {
        val indexOf = customOrder.withIndex().associate { it.value to it.index }
        folders.sortedWith(
            compareBy<FolderGroup> { indexOf[it.key.toString()] ?: Int.MAX_VALUE }
                .thenByDescending { it.latest }
        )
    }
}

/** Row used in Custom order: tap to open, arrows to reposition. */
@Composable
private fun FolderOrderRow(
    folder: FolderGroup,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onOpen: () -> Unit
) {
    val cover = folder.cover
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, cover?.uri) {
        value = cover?.let { MediaStoreRepository.loadThumbnail(context, it, 128) }
    }
    val animatedGif = rememberAnimatedGif(cover)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect
            )
            .semantics { contentDescription = "Folder ${folder.name}" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            val coverBitmap = thumbnail
            val coverGif = animatedGif
            if (coverGif != null) {
                AnimatedGifImage(coverGif, modifier = Modifier.fillMaxSize())
            } else if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            if (selectionMode) {
                SelectionBadge(selected = selected)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (folder.items.size == 1) "1 item" else "${folder.items.size} items",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        NamedActionIconButton(
            name = "Move up",
            icon = Icons.Filled.KeyboardArrowUp,
            onClick = onMoveUp,
            enabled = canMoveUp
        )
        NamedActionIconButton(
            name = "Move down",
            icon = Icons.Filled.KeyboardArrowDown,
            onClick = onMoveDown,
            enabled = canMoveDown
        )
    }
}

@Composable
private fun EmptyFolderContent(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(36.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FolderTile(
    folder: FolderGroup,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    // Produced unconditionally — a conditional composable call would break
    // composition when a user-created (empty) folder gains its first file.
    val cover = folder.cover
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, cover?.uri) {
        value = cover?.let { MediaStoreRepository.loadThumbnail(context, it, 192) }
    }
    val animatedGif = rememberAnimatedGif(cover)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onClick() },
                onLongClick = onToggleSelect
            )
            .semantics {
                contentDescription = if (folder.items.isEmpty()) {
                    "Folder ${folder.name}, empty"
                } else {
                    "Folder ${folder.name}, ${folder.items.size} items"
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            val coverBitmap = thumbnail
            val coverGif = animatedGif
            if (coverGif != null) {
                AnimatedGifImage(coverGif, modifier = Modifier.fillMaxSize())
            } else if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    when (cover?.kind) {
                        MediaKind.DOCUMENT -> Icons.Filled.Description
                        MediaKind.APK -> Icons.Filled.GetApp
                        else -> Icons.Filled.Folder
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp)
                )
            }

            if (selectionMode) {
                SelectionBadge(selected = selected)
            }

            if (folder.items.isNotEmpty() && !selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        folder.items.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            folder.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            when {
                folder.userCreated && folder.items.isEmpty() -> "Empty folder — tap to view"
                folder.items.size == 1 -> "1 item"
                else -> "${folder.items.size} items"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/** Drill-in header: back affordance, folder name, honest item count. */
@Composable
private fun FolderHeader(
    name: String,
    count: Int,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NamedActionIconButton(
            name = "Back to folders",
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onBack
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$count ${if (count == 1) "item" else "items"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun NewFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val usable = trimmed.isNotEmpty() &&
        trimmed.length <= 48 &&
        !trimmed.contains('/') &&
        !trimmed.contains('\\') &&
        trimmed.none { it.isISOControl() } &&
        trimmed != "." &&
        trimmed != ".."

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Folder name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(trimmed) }, enabled = usable) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RenameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val usable = trimmed.isNotEmpty() &&
        trimmed.length <= 120 &&
        !trimmed.contains('/') &&
        !trimmed.contains('\\') &&
        trimmed.none { it.isISOControl() } &&
        trimmed != "." &&
        trimmed != ".." &&
        trimmed != initial

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("New name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = usable) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun MoveCopyDialog(
    action: MediaAction,
    destinations: List<String>,
    selectionCount: Int,
    onDismiss: () -> Unit,
    onNewFolder: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var chosen by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (selectionCount == 1) {
                    "${action.dialogTitle} 1 item to…"
                } else {
                    "${action.dialogTitle} $selectionCount items to…"
                }
            )
        },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNewFolder() }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "New folder…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))

                if (destinations.isEmpty()) {
                    Text(
                        "No folders on this device yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        destinations.forEach { folder ->
                            val selected = chosen == folder
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { chosen = folder }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { chosen = folder }
                                )
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    folder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let(onConfirm) }, enabled = chosen != null) {
                Text(action.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FolderInfoDialog(
    folder: FolderGroup,
    onClose: () -> Unit
) {
    val photos = folder.items.count { it.kind == MediaKind.IMAGE }
    val videos = folder.items.count { it.kind == MediaKind.VIDEO }
    val audios = folder.items.count { it.kind == MediaKind.AUDIO }
    val breakdown = listOfNotNull(
        photos.takeIf { it > 0 }?.let { "$it photo${if (it == 1) "" else "s"}" },
        videos.takeIf { it > 0 }?.let { "$it video${if (it == 1) "" else "s"}" },
        audios.takeIf { it > 0 }?.let { "$it audio${if (it == 1) "" else "s"}" }
    ).joinToString(" · ").ifBlank { "No files" }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(folder.name) },
        text = {
            Column {
                InfoRow("Address", folder.path ?: "Unavailable")
                InfoRow("Items", "${folder.items.size} — $breakdown")
                InfoRow("Size", formatSize(folder.bytes))
                InfoRow("Created", folder.oldestAdded?.let(::formatDate) ?: "—")
                InfoRow("Last modified", folder.newestModified?.let(::formatDate) ?: "—")
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

// ------------------------------------------------------------- empty states

@Composable
private fun EmptyTab(tab: GalleryTab) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            when (tab) {
                GalleryTab.PHOTOS -> Icons.Filled.Image
                GalleryTab.VIDEOS -> Icons.Filled.Movie
                GalleryTab.AUDIO -> Icons.Filled.MusicNote
                GalleryTab.DOCUMENTS -> Icons.Filled.Description
                GalleryTab.APK -> Icons.Filled.GetApp
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            when (tab) {
                GalleryTab.PHOTOS -> "No photos found"
                GalleryTab.VIDEOS -> "No videos found"
                GalleryTab.AUDIO -> "No audio found"
                GalleryTab.DOCUMENTS -> "No documents found"
                GalleryTab.APK -> "No APK files found"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Anything saved to this device will show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

// ------------------------------------------------------------- file ops

/** Renames one file, preferring MediaStore so its row follows the new name. */
private fun renameMediaItem(context: Context, item: MediaItem, newName: String): Boolean {
    val viaProvider = runCatching {
        context.contentResolver.update(
            item.uri,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) },
            null,
            null
        )
    }.getOrDefault(0)
    if (viaProvider > 0) return true

    val path = MediaStoreRepository.filePathFor(context, item) ?: return false
    val source = java.io.File(path)
    val target = java.io.File(source.parentFile ?: return false, newName)
    if (!source.exists() || target.exists()) return false
    if (!source.renameTo(target)) return false
    android.media.MediaScannerConnection.scanFile(
        context, arrayOf(path, target.absolutePath), arrayOf(item.mimeType), null
    )
    return true
}

/**
 * Renames the directory on disk (all-files access makes this a plain rename)
 * and rescans every contained file at its new path so MediaStore follows.
 */
private fun renameFolderOnDisk(context: Context, group: FolderGroup, newName: String): Boolean {
    val oldDir = group.path?.let { java.io.File(it.trimEnd('/')) } ?: return false
    if (!oldDir.isDirectory) return false
    val parent = oldDir.parentFile ?: return false
    val target = java.io.File(parent, newName)
    if (target.exists()) return false

    val contained = runCatching { oldDir.walkTopDown().filter { it.isFile }.toList() }
        .getOrDefault(emptyList())
    if (!oldDir.renameTo(target)) return false

    if (contained.isNotEmpty()) {
        val newPaths = contained.map { file ->
            java.io.File(target, file.relativeTo(oldDir).path).absolutePath
        }.toTypedArray()
        android.media.MediaScannerConnection.scanFile(context, newPaths, null, null)
    }
    return true
}

/** Copies one media item into `<storage root>/<folder>/`; returns its new path. */
private suspend fun copyIntoFolder(
    context: Context,
    item: MediaItem,
    folder: String
): String? = withContext(Dispatchers.IO) {
    // Copies INSIDE the same folder would clash names, so they get a suffix.
    val sameFolder = item.bucket?.equals(folder, ignoreCase = true) == true
    val base = item.name.substringBeforeLast('.', item.name)
    val ext = item.name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
    val displayName = if (sameFolder) "$base (copy)$ext" else item.name

    val root = FolderMaker.sharedStorageRoot()
    val destDir = java.io.File(root, folder)
    if (!destDir.exists() && !destDir.mkdirs()) return@withContext null

    var dest = java.io.File(destDir, displayName)
    var suffix = 1
    while (dest.exists()) {
        dest = java.io.File(destDir, "$base ($suffix)$ext")
        suffix++
    }

    val ok = runCatching {
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } != null && dest.length() > 0
    }.getOrDefault(false)
    if (ok) dest.absolutePath else null
}

/** Decodes an animated GIF once per item; null for everything else. */
@Composable
private fun rememberAnimatedGif(item: MediaItem?): Drawable? {
    val context = LocalContext.current
    return produceState<Drawable?>(initialValue = null, item?.uri) {
        value = item?.let { media ->
            withContext(Dispatchers.IO) { MediaStoreRepository.loadAnimatedGif(context, media) }
        }
    }.value
}

/** Hosts the self-animating drawable inside Compose. */
@Composable
private fun AnimatedGifImage(drawable: Drawable, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(drawable)
            }
        },
        modifier = modifier
    )
}

/** Decodes a grid-tile thumbnail off the main thread, once per item. */
@Composable
private fun rememberThumbnail(item: MediaItem, size: Int): Bitmap? {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = MediaStoreRepository.loadThumbnail(context, item, size)
    }
    return thumbnail
}
