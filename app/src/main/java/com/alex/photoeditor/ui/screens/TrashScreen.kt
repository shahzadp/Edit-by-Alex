package com.alex.photoeditor.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TooltipDefaults
import com.alex.photoeditor.core.MediaKind
import com.alex.photoeditor.core.SystemTrash
import com.alex.photoeditor.core.TrashStore
import com.alex.photoeditor.ui.GalleryViewModel
import com.alex.photoeditor.ui.rememberSafeArea
import com.alex.photoeditor.ui.components.NamedActionIconButton
import com.alex.photoeditor.ui.components.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.alex.photoeditor.ui.components.formatDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The bin — both what this app deleted, and what the system Trash holds.
 *
 * App-deleted files live in app-private storage with a JSON index, so they
 * can be restored by moving them back or erased permanently. Additionally
 * the screen sweeps the system Trash (media rows with IS_TRASHED = 1, and
 * any `.Trash-*` directories at the volume root) so files trashed by other
 * apps and non-media files show up here too.
 *
 * System-trashed media are restored through the system's own restore thunk
 * (createTrashRequest, with quiet-provider and filesystem fallbacks);
 * filesystem-only items have no known original location, so restoring moves
 * them to Downloads/Edit by Alex Restored/.
 *
 * The top bar has a circular select-all toggle; long names wrap so rows grow
 * rather than clip. Selecting files reveals a bucket action (DeleteSweep)
 * that erases the selection permanently.
 */
@Composable
fun TrashScreen(vm: GalleryViewModel) {
    val safeArea = rememberSafeArea()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<TrashRowData?>(null) }
    var previewRow by remember { mutableStateOf<TrashRowData?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    LaunchedEffect(Unit) { vm.refreshTrash() }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // The system dialog is the only restore path that needs a re-query;
        // even here we leave the gallery alone — its lists update in place.
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            vm.refreshTrash()
        } else {
            android.widget.Toast.makeText(
                context,
                "Restore didn't complete. If this file was trashed by another app, restore it from that app.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // System-trashed items restore in stages inside the VM: a quiet provider
    // untrash, a filesystem move, then (as a last resort) the system's own
    // restore request — which throws synchronously for files owned by another
    // app, so it is built here, wrapped in runCatching.
    fun restore(row: TrashRowData) {
        when {
            row.fromThisApp -> row.appEntry?.let { vm.restoreTrashEntry(it.id) }
            row.systemEntry != null -> {
                val entry = row.systemEntry
                vm.restoreSystemTrashEntry(entry) { fallbackEntry ->
                    val sender = runCatching {
                        android.provider.MediaStore.createTrashRequest(
                            context.contentResolver,
                            listOf(fallbackEntry.uri!!),
                            false
                        )
                    }.getOrNull()
                    if (sender != null) {
                        restoreLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "Restore blocked by the system for this file. Restore it from the Files app.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    BackHandler { vm.closeTrash() }

    // Error rows aren't merged here; the VM merges and sorts for us.
    val rows = remember(vm.trashEntries, vm.systemTrashItems) {
        buildList {
            vm.trashEntries.forEach { entry ->
                add(
                    TrashRowData(
                        id = entry.id,
                        name = entry.name,
                        mimeType = entry.mimeType,
                        sizeBytes = entry.sizeBytes,
                        dateSeconds = entry.deletedAt / 1000,
                        kind = runCatching { MediaKind.valueOf(entry.kind) }.getOrNull(),
                        fromThisApp = true,
                        appEntry = entry,
                        systemEntry = null
                    )
                )
            }
            vm.systemTrashItems.forEach { entry ->
                add(
                    TrashRowData(
                        id = "system:${entry.id}",
                        name = entry.name,
                        mimeType = entry.mimeType,
                        sizeBytes = entry.sizeBytes,
                        dateSeconds = entry.dateAddedSeconds,
                        kind = entry.kind,
                        fromThisApp = false,
                        appEntry = null,
                        systemEntry = entry
                    )
                )
            }
        }.sortedByDescending { it.dateSeconds }
    }

    // Drop selections that no longer exist (e.g. after a delete or refresh).
    LaunchedEffect(rows) {
        if (selectedIds.isNotEmpty()) {
            selectedIds = selectedIds intersect rows.map { it.id }.toSet()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = safeArea.top, start = safeArea.start, end = safeArea.end)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NamedActionIconButton(
                name = "Back",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = vm::closeTrash
            )
            Text(
                "Trash",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    .combinedClickable(onClick = {}, onLongClick = { showDiagnostics = true })
            )
            Spacer(Modifier.width(4.dp))
            if (selectionMode) {
                Text(
                    "${selectedIds.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                NamedActionIconButton(
                    name = "Delete selected forever",
                    icon = Icons.Filled.DeleteSweep,
                    onClick = { confirmDeleteSelected = true },
                    tint = MaterialTheme.colorScheme.error
                )
            }
            if (rows.isNotEmpty()) {
                CircleCheckToggle(
                    checked = selectedIds.size == rows.size,
                    onClick = {
                        selectedIds =
                            if (selectedIds.size == rows.size) emptySet() else rows.map { it.id }.toSet()
                    },
                    name = "Select all / none"
                )
            }
        }

        if (rows.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.RestoreFromTrash,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Trash is empty",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Anything deleted here and anything in your device's trash ends up in this screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        } else {
            Text(
                "${rows.size} ${if (rows.size == 1) "file" else "files"} · ${formatSize(vm.trashSizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp)
            )
            Text(
                "Old bin files are removed automatically after 7 days or when the bin passes 512 MB.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 2.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)
            ) {
                items(rows, key = { it.id }) { row ->
                    TrashRow(
                        row = row,
                        selectionMode = selectionMode,
                        selected = row.id in selectedIds,
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (row.id in selectedIds) {
                                    selectedIds - row.id
                                } else {
                                    selectedIds + row.id
                                }
                            } else if (isApkTrashFile(row.name, row.mimeType)) {
                                // APK rows are not previewable while trashed.
                                Unit
                            } else {
                                previewRow = row
                            }
                        },
                        onLongPress = {
                            selectedIds = if (row.id in selectedIds) {
                                selectedIds - row.id
                            } else {
                                selectedIds + row.id
                            }
                        },
                        onRestore = { restore(row) },
                        onDeleteForever = { confirmDelete = row }
                    )
                }
            }
        }
    }

    previewRow?.let { row ->
        TrashPreviewOverlay(
            row = row,
            onClose = { previewRow = null },
            onRestore = {
                restore(row)
                previewRow = null
            },
            onDeleteForever = {
                when {
                    row.fromThisApp -> row.appEntry?.let { vm.deleteTrashEntryForever(it.id) }
                    else -> row.systemEntry?.let { vm.deleteSystemTrashEntryForever(it) }
                }
                previewRow = null
            }
        )
    }

    if (showDiagnostics) {
        val root = com.alex.photoeditor.core.FolderMaker.sharedStorageRoot()
        val fromProvider = vm.systemTrashItems.count { it.uri != null }
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Trash diagnostics") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("All-files access: ${com.alex.photoeditor.ui.components.hasAllFilesAccess(context)}")
                    Text("Storage root: ${root.absolutePath}")
                    Text("Root entries visible: ${runCatching { root.listFiles()?.size }.getOrNull() ?: -1}")
                    Text("MediaStore trash rows: $fromProvider · filesystem rows: ${vm.systemTrashItems.size - fromProvider}")
                    Text("App bin rows: ${vm.trashEntries.size}")
                    Text(
                        "Bin on disk: ${formatSize(com.alex.photoeditor.core.TrashStore.trashDir(context).listFiles()?.filter { it.isFile && it.name != "trash.json" }?.sumOf { it.length() } ?: 0L)}" +
                            " · in index: ${formatSize(vm.trashEntries.sumOf { it.sizeBytes })}"
                    )
                    Text("Samples (up to 8):", style = MaterialTheme.typography.labelSmall)
                    vm.systemTrashItems.take(8).forEach { entry ->
                        Text(
                            "${entry.name} — ${entry.path ?: entry.uri}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val removed = vm.cleanupTrashNow()
                    android.widget.Toast.makeText(
                        context,
                        if (removed > 0) "Removed $removed old file(s)" else "Nothing to clean",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) { Text("Clean now") }
            }
        )
    }

    if (confirmDeleteSelected) {
        val selectedRows = rows.filter { it.id in selectedIds }
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("Delete selected permanently?") },
            text = {
                Text("${selectedRows.size} ${if (selectedRows.size == 1) "file" else "files"} will be deleted permanently. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteSelected = false
                        selectedRows.forEach { row ->
                            when {
                                row.fromThisApp -> row.appEntry?.let { vm.deleteTrashEntryForever(it.id) }
                                else -> row.systemEntry?.let { vm.deleteSystemTrashEntryForever(it) }
                            }
                        }
                        selectedIds = emptySet()
                    }
                ) {
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete permanently?") },
            text = {
                Text("\"${row.name}\" will never come back. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        when {
                            row.fromThisApp -> row.appEntry?.let { vm.deleteTrashEntryForever(it.id) }
                            else -> row.systemEntry?.let { vm.deleteSystemTrashEntryForever(it) }
                        }
                    }
                ) {
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** A unified row model merging app-binned and system-trashed items. */
internal data class TrashRowData(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Seconds since epoch, for sorting and display. */
    val dateSeconds: Long,
    val kind: MediaKind?,
    val fromThisApp: Boolean,
    val appEntry: TrashStore.TrashEntry?,
    val systemEntry: SystemTrash.Entry?
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TrashRow(
    row: TrashRowData,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, row.appEntry?.storedPath, row.systemEntry?.path) {
        value = withContext(Dispatchers.IO) {
            val path = row.appEntry?.storedPath ?: row.systemEntry?.path
            path?.let { decodeImageFile(it, 256) }
        }
    }
    val apkIcon by produceState<Bitmap?>(initialValue = null, row.id) {
        value = withContext(Dispatchers.IO) { loadApkIcon(context, row) }
    }
    val dateText = remember(row.dateSeconds) {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(row.dateSeconds * 1000))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .semantics {
                contentDescription = when {
                    selectionMode -> if (selected) "Deselect ${row.name}" else "Select ${row.name}"
                    isApkTrashFile(row.name, row.mimeType) -> "APK ${row.name} — restore to open"
                    else -> "Preview ${row.name}"
                }
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            SelectionCircle(selected = selected)
            Spacer(Modifier.width(10.dp))
        }
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = thumb
            val appIcon = apkIcon
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (appIcon != null) {
                Image(
                    bitmap = appIcon.asImageBitmap(),
                    contentDescription = "App icon",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                )
            } else {
                Icon(
                    when (row.kind) {
                        MediaKind.VIDEO -> Icons.Filled.Movie
                        MediaKind.AUDIO -> Icons.Filled.MusicNote
                        MediaKind.IMAGE -> Icons.Filled.Image
                        MediaKind.DOCUMENT -> Icons.Filled.Description
                        MediaKind.APK, null -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                (if (isApkTrashFile(row.name, row.mimeType)) "APK · " else "") +
                    "${formatSize(row.sizeBytes)} · $dateText · ${if (row.fromThisApp) "deleted here" else "device trash"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Restore to original location") } },
            state = rememberTooltipState()
        ) {
            OutlinedButton(
                onClick = onRestore,
                modifier = Modifier.padding(end = 2.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Text("Restore")
            }
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Delete forever — this cannot be undone") } },
            state = rememberTooltipState()
        ) {
            OutlinedButton(
                onClick = onDeleteForever,
                modifier = Modifier.semantics { contentDescription = "Delete ${row.name} forever" },
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Filled circle + tick when selected, hollow circle when not. Mirrors [GalleryScreen]'s badge. */
@Composable
private fun SelectionCircle(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
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

/** Circular toggle matching [SelectionCircle]: filled + tick when on, hollow when off. */
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

/** Decodes a local image file as a small thumbnail, or null. */
private fun decodeImageFile(path: String, maxSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
