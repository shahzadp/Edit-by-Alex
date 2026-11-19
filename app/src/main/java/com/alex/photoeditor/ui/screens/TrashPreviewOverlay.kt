package com.alex.photoeditor.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.alex.photoeditor.core.MediaKind
import com.alex.photoeditor.core.TrashStore
import com.alex.photoeditor.ui.components.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen preview of a binned file, before restoring.
 *
 * Images, video and audio are rendered in place. Non-media files get a
 * details card with an "Open with…" button — the system hands them to the
 * right app (PDF reader, archive manager, installer) using the app's
 * FileProvider grants rather than trying to decode them as media.
 */
@Composable
internal fun TrashPreviewOverlay(
    row: TrashRowData,
    onClose: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val context = LocalContext.current

    BackHandler(onBack = onClose)

    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Preview area
        when (row.kind) {
            MediaKind.IMAGE -> ImagePreview(row)
            MediaKind.VIDEO -> VideoPreview(row, onOpenWith = { openWith(context, row) })
            MediaKind.AUDIO -> AudioPreview(row)
            null -> NonMediaPreview(row, onOpenWith = { openWith(context, row) })
        }

        // Top scrim
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close preview",
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatSize(row.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        // Bottom scrim with the two honest actions
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onRestore,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Filled.RestoreFromTrash,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Restore")
            }
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text("Delete forever", color = MaterialTheme.colorScheme.error)
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete permanently?") },
                text = {
                    Text("\"${row.name}\" will never come back. This cannot be undone.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        onDeleteForever()
                    }) {
                        Text("Delete forever", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun ImagePreview(row: TrashRowData) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, row.id) {
        value = withContext(Dispatchers.IO) { decodeImage(context, row) }
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange * scale else androidx.compose.ui.geometry.Offset.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(transformableState)
            .pointerInput(row.id) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1.01f) 1f else 2.5f
                    if (scale == 1f) offset = androidx.compose.ui.geometry.Offset.Zero
                })
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = row.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }
    }
}

@Composable
private fun VideoPreview(
    row: TrashRowData,
    onOpenWith: () -> Unit
) {
    val context = LocalContext.current
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }

    LaunchedEffect(isPlaying, videoView) {
        while (isPlaying) {
            videoView?.let { position = it.currentPosition }
            kotlinx.coroutines.delay(150)
        }
    }

    if (failed) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "This video could not be played in-app.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onOpenWith) {
                Text("Open with another app")
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setOnPreparedListener { mp ->
                        prepared = true
                        duration = mp.duration
                        mp.start()
                        isPlaying = true
                    }
                    setOnCompletionListener { isPlaying = false }
                    setOnErrorListener { _, _, _ ->
                        failed = true
                        true
                    }
                    val path = row.appEntry?.storedPath ?: row.systemEntry?.path
                    val uri = row.systemEntry?.uri
                    when {
                        path != null && File(path).exists() -> setVideoPath(path)
                        uri != null -> setVideoURI(uri)
                        else -> failed = true
                    }
                    videoView = this
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (!prepared) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        val view = videoView ?: return@clickable
                        if (view.isPlaying) {
                            view.pause()
                            isPlaying = false
                        } else {
                            view.start()
                            isPlaying = true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (!isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Resume video",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }

        // Bottom scrim with play position (within the 84dp bottom action bar)
        Text(
            formatPosition(position) + " / " + formatPosition(duration),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp, start = 220.dp, end = 220.dp)
        )
    }
}

@Composable
private fun AudioPreview(row: TrashRowData) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }

    DisposableEffect(row.id) {
        val mp = MediaPlayer()
        runCatching {
            val path = row.appEntry?.storedPath ?: row.systemEntry?.path
            val uri = row.systemEntry?.uri
            when {
                path != null -> mp.setDataSource(path)
                uri != null -> mp.setDataSource(context, uri)
                else -> throw IllegalStateException("No source for ${row.name}")
            }
            mp.setOnPreparedListener {
                prepared = true
                duration = it.duration
                it.start()
                isPlaying = true
            }
            mp.setOnCompletionListener { isPlaying = false }
            mp.setOnErrorListener { _, _, _ -> failed = true; true }
            mp.prepareAsync()
        }.onFailure { failed = true }
        player = mp
        onDispose {
            mp.runCatching { stop() }
            mp.release()
        }
    }

    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            player?.let { position = runCatching { it.currentPosition }.getOrDefault(position) }
            kotlinx.coroutines.delay(400)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(Color(0xFF1E1E26)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color(0xFFB4B4C0),
                modifier = Modifier.size(72.dp)
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            row.name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(40.dp))

        if (failed) {
            Text("Could not play this audio.", color = Color.White.copy(alpha = 0.7f))
        } else if (!prepared) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        val mp = player ?: return@clickable
                        if (isPlaying) {
                            mp.pause()
                            isPlaying = false
                        } else {
                            if (position >= duration - 200) {
                                mp.seekTo(0)
                                position = 0
                            }
                            mp.start()
                            isPlaying = true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                formatPosition(position) + " / " + formatPosition(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun NonMediaPreview(
    row: TrashRowData,
    onOpenWith: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(
            Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = Color(0xFFB4B4C0),
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            row.name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Text(
            row.mimeType,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )
        Text(
            formatSize(row.sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onOpenWith) {
            Text("Open with another app")
        }
    }
}

private fun resolveUri(context: Context, row: TrashRowData): android.net.Uri? = when {
    row.appEntry != null -> FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(row.appEntry.storedPath)
    )
    row.systemEntry?.uri != null -> row.systemEntry.uri
    else -> row.systemEntry?.path?.let {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(it)
        )
    }
}

/** Opens the row in whichever app handles its MIME type. */
private fun openWith(context: Context, row: TrashRowData) {
    val uri = resolveUri(context, row) ?: return

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, row.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, "trash", uri)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open with"))
    }.onFailure {
        android.widget.Toast.makeText(
            context,
            "Can't open this file while it's in the trash — restore it first.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

/** Decodes an image from the row, downsampled to fit the preview window. */
private fun decodeImage(context: Context, row: TrashRowData): Bitmap? {
    val path = row.appEntry?.storedPath ?: row.systemEntry?.path
    val uri = row.systemEntry?.uri

    val target = when (row.kind) {
        MediaKind.IMAGE -> 2048
        else -> return null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    when {
        path != null -> BitmapFactory.decodeFile(path, bounds)
        uri != null -> context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        else -> return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    var longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    while (longEdge / 2 >= target) {
        longEdge /= 2
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return when {
        path != null -> BitmapFactory.decodeFile(path, opts)
        uri != null -> context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        else -> null
    }
}


private fun formatPosition(ms: Int): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val m = s / 60
    val sec = s % 60
    return "%d:%02d".format(m, sec)
}
