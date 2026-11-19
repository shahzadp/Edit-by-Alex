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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
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
 * ([restoreFromTrash]); filesystem-only items have no known original
 * location, so restoring moves them to the shared-storage root.
 */
@Composable
fun TrashScreen(vm: GalleryViewModel) {
    val safeArea = rememberSafeArea()
    val context = LocalContext.current
    var showEmptyConfirm by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TrashRowData?>(null) }

    LaunchedEffect(Unit) { vm.refreshTrash() }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // The system restore is asynchronous; when it returns, refresh both
        // the bin and the gallery so restored files show up without manual refresh.
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            vm.refreshTrash()
            vm.refresh()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = safeArea.top, start = safeArea.start, end = safeArea.end)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
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
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            if (rows.isNotEmpty()) {
                TextButton(onClick = { showEmptyConfirm = true }) {
                    Text("Empty trash")
                }
                Spacer(Modifier.width(4.dp))
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
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)
            ) {
                items(rows, key = { it.id }) { row ->
                    TrashRow(
                        row = row,
                        onRestore = {
                            when {
                                row.fromThisApp -> row.appEntry?.let { vm.restoreTrashEntry(it.id) }
                                // Media items go through the system's own restore;
                                // filesystem-only items move back to the shared root.
                                row.systemEntry?.uri != null -> {
                                    val entry = row.systemEntry
                                    val sender = android.provider.MediaStore.createTrashRequest(
                                        context.contentResolver,
                                        listOf(entry.uri),
                                        false
                                    )
                                    restoreLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                }
                                row.systemEntry != null ->
                                    vm.restoreSystemTrashEntryFromFilesystem(row.systemEntry)
                            }
                        },
                        onDeleteForever = { confirmDelete = row }
                    )
                }
            }
        }
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = { Text("Empty Trash?") },
            text = {
                Text("${rows.size} ${if (rows.size == 1) "file" else "files"} will be deleted permanently. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyConfirm = false
                        vm.emptyTrash()
                    }
                ) {
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) {
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
private data class TrashRowData(
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
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val thumb by produceState<Bitmap?>(initialValue = null, row.appEntry?.storedPath, row.systemEntry?.path) {
        value = withContext(Dispatchers.IO) {
            val path = row.appEntry?.storedPath ?: row.systemEntry?.path
            path?.let { decodeImageFile(it, 256) }
        }
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
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = thumb
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    when (row.kind) {
                        MediaKind.VIDEO -> Icons.Filled.Movie
                        MediaKind.AUDIO -> Icons.Filled.MusicNote
                        MediaKind.IMAGE -> Icons.Filled.Image
                        null -> Icons.AutoMirrored.Filled.InsertDriveFile
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
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
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
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text("Restore")
            }
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Delete forever — this cannot be undone") } },
            state = rememberTooltipState()
        ) {
            IconButton(
                onClick = onDeleteForever,
                modifier = Modifier.semantics { contentDescription = "Delete ${row.name} forever" }
            ) {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
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
