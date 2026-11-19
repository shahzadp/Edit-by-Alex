package com.alex.photoeditor.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alex.photoeditor.core.MediaItem
import com.alex.photoeditor.core.MediaKind
import com.alex.photoeditor.core.MediaStoreRepository
import com.alex.photoeditor.ui.components.NamedActionIconButton
import kotlinx.coroutines.delay

/**
 * Fullscreen, dark-room media viewer.
 *
 * Mirrors what a gallery app opens into: swipe between items of the same tab,
 * pinch-zoom photos, play videos and audio in place. The top bar carries the
 * three honest actions — Share, Edit (images only, hands the tag to the
 * editor) and Info; a close action is Back.
 *
 * Video and audio play through the platform player rather than a bundled one:
 * every format the device can play is playable here for free.
 */
@Composable
fun MediaViewer(
    items: List<MediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onEditPhoto: (Uri) -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    ) { items.size }

    var showInfo by remember { mutableStateOf(false) }
    var transientMessage by remember { mutableStateOf<String?>(null) }

    // The viewer owns the back gesture while it is up: leaving should land on
    // the grid, not on whatever the editor had underneath.
    BackHandler(onBack = onDismiss)

    val current = items.getOrNull(pagerState.currentPage)

    // Pinch-zoom is meaningless if the pager swipes the page away mid-pinch,
    // so while any photo is zoomed the swipe is suspended. PhotoPage reports
    // its zoom state up; the last report wins, which is correct in practice
    // because only the visible page can produce gestures.
    var zoomed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            val isCurrent = pagerState.currentPage == page
            when (item.kind) {
                MediaKind.IMAGE -> PhotoPage(
                    item,
                    onZoomChanged = { zoomed = it },
                    onOpenWith = { openWith(context, item)?.let { transientMessage = it } }
                )
                MediaKind.VIDEO -> VideoPage(item, isCurrent, onOpenWith = {
                    openWith(context, item)?.let { transientMessage = it }
                })
                MediaKind.AUDIO -> AudioPage(item, isCurrent, onOpenWith = {
                    openWith(context, item)?.let { transientMessage = it }
                })
            }
        }

        // Top scrim so the controls stay legible over any photo.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NamedActionIconButton(
                name = "Back",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onDismiss,
                tint = Color.White,
                contentDescription = "Close viewer"
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    current?.displayName ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${pagerState.currentPage + 1} of ${items.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            current?.let { item ->
                if (item.kind == MediaKind.IMAGE && !MediaStoreRepository.isSvgItem(item)) {
                    NamedActionIconButton(
                        name = "Edit in editor",
                        icon = Icons.Filled.Edit,
                        onClick = { onEditPhoto(item.uri) },
                        tint = Color.White
                    )
                }
                NamedActionIconButton(
                    name = "Share",
                    icon = Icons.Filled.Share,
                    onClick = { shareMedia(context, item) },
                    tint = Color.White
                )
                NamedActionIconButton(
                    name = "Details",
                    icon = Icons.Filled.Info,
                    onClick = { showInfo = true },
                    tint = Color.White
                )
            }
        }

        // A one-shot message pill, used when an "open with" intent finds no
        // handler. Not a snackbar: it lives over the black stage, not the
        // editor's chrome.
        transientMessage?.let { message ->
            LaunchedEffect(message) {
                delay(2600)
                transientMessage = null
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp, start = 32.dp, end = 32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xE6222228))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(message, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (showInfo && current != null) {
            MediaInfoDialog(item = current, onClose = { showInfo = false })
        }
    }
}

// ---------------------------------------------------------------- photo page

/** Result of the full-image decode, so failure never looks like a spinner. */
private sealed interface PhotoLoadState {
    data object Loading : PhotoLoadState
    data object Failed : PhotoLoadState
    data class Ready(val bitmap: Bitmap) : PhotoLoadState
}

@Composable
private fun PhotoPage(
    item: MediaItem,
    onZoomChanged: (Boolean) -> Unit,
    onOpenWith: () -> Unit
) {
    val context = LocalContext.current
    // Loading and failure are different states on purpose: a file the
    // platform cannot decode used to leave the spinner up forever, which is
    // indistinguishable from "the app froze".
    val state by produceState<PhotoLoadState>(PhotoLoadState.Loading, item.uri) {
        value = runCatching { MediaStoreRepository.loadFullImage(context, item) }
            .fold(
                onSuccess = { bmp -> if (bmp != null) PhotoLoadState.Ready(bmp) else PhotoLoadState.Failed },
                onFailure = { PhotoLoadState.Failed }
            )
    }

    var scale by remember(item.id) { mutableStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        // Panning only makes sense once zoomed in; below 1x the photo should
        // sit dead centre rather than drift off screen.
        offset = if (scale > 1f) offset + panChange * scale else Offset.Zero
    }

    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.01f)
    }

    if (state is PhotoLoadState.Failed) {
        PlaybackFailure(
            message = "This image could not be opened.",
            onOpenWith = onOpenWith
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(transformableState)
            .pointerInput(item.id) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1.01f) 1f else 2.5f
                    if (scale == 1f) offset = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = (state as? PhotoLoadState.Ready)?.bitmap
        if (bmp == null) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.displayName,
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

// ---------------------------------------------------------------- video page

@Composable
private fun VideoPage(
    item: MediaItem,
    isCurrent: Boolean,
    onOpenWith: () -> Unit
) {
    val context = LocalContext.current
    var videoView by remember(item.id) { mutableStateOf<VideoView?>(null) }
    var prepared by remember(item.id) { mutableStateOf(false) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var isPlaying by remember(item.id) { mutableStateOf(false) }
    var position by remember(item.id) { mutableStateOf(0L) }
    var duration by remember(item.id) { mutableStateOf(0L) }
    var scrubPosition by remember(item.id) { mutableStateOf<Float?>(null) }

    // Poll position only while playing; a sleeping Compose screen should not
    // burn frames reading a player it is not watching.
    LaunchedEffect(isPlaying, videoView) {
        while (isPlaying) {
            videoView?.let { position = it.currentPosition.toLong() }
            delay(200)
        }
    }

    // Swiping the pager away pauses: audio bleeding out of an invisible video
    // is the classic viewer bug.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            videoView?.pause()
            isPlaying = false
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }

    if (failed) {
        PlaybackFailure(
            message = "This video could not be played.",
            onOpenWith = onOpenWith
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    // VideoView does not letterbox for you: it measures itself at
                    // the video's pixel size, which overflows the screen on a
                    // large capture. Size it in onPrepared instead, once the
                    // true dimensions are known, scaling to the full width.
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setVideoURI(item.uri)
                    setOnPreparedListener { mp ->
                        prepared = true
                        duration = mp.duration.toLong()
                        mp.isLooping = false
                        // Opening a video is a request to watch it: start
                        // immediately rather than waiting for a tap.
                        mp.start()
                        isPlaying = true
                        val videoW = mp.videoWidth
                        val videoH = mp.videoHeight
                        if (videoW > 0 && videoH > 0) {
                            val parentWidth = ctx.resources.displayMetrics.widthPixels
                            val scaledHeight = (parentWidth.toFloat() * videoH / videoW).toInt()
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                scaledHeight
                            )
                            requestLayout()
                        }
                    }
                    setOnCompletionListener {
                        isPlaying = false
                        position = duration
                    }
                    setOnErrorListener { _, _, _ ->
                        failed = true
                        true
                    }
                    videoView = this
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Tap the frame toggles playback; the controls stay out of the way
        // otherwise.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    val view = videoView ?: return@clickable
                    if (view.isPlaying) {
                        view.pause()
                        isPlaying = false
                    } else {
                        // Tapping again after the video finished replays from
                        // the start instead of sitting at the last frame.
                        if (duration > 0 && position >= duration - 500) {
                            view.seekTo(0)
                            position = 0
                        }
                        view.start()
                        isPlaying = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (!prepared) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else if (!isPlaying) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        if (prepared) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    formatDuration(scrubPosition?.toLong() ?: position),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                Slider(
                    value = scrubPosition ?: position.coerceAtMost(duration).toFloat(),
                    onValueChange = { scrubPosition = it },
                    onValueChangeFinished = {
                        scrubPosition?.let { value ->
                            videoView?.seekTo(value.toInt())
                            position = value.toLong()
                        }
                        scrubPosition = null
                    },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    formatDuration(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- audio page

@Composable
private fun AudioPage(
    item: MediaItem,
    isCurrent: Boolean,
    onOpenWith: () -> Unit
) {
    val context = LocalContext.current
    var player by remember(item.id) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(item.id) { mutableStateOf(false) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var isPlaying by remember(item.id) { mutableStateOf(false) }
    var position by remember(item.id) { mutableStateOf(0L) }
    var duration by remember(item.id) { mutableStateOf(0L) }
    var scrubPosition by remember(item.id) { mutableStateOf<Float?>(null) }

    val art by produceState<Bitmap?>(initialValue = null, item.id) {
        value = MediaStoreRepository.loadThumbnail(context, item, 512)
    }

    // One MediaPlayer per audio page, released the moment the page goes away.
    DisposableEffect(item.id) {
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, item.uri)
                setOnPreparedListener { preparedMp ->
                    prepared = true
                    duration = preparedMp.duration.toLong().coerceAtLeast(0)
                    preparedMp.start()
                    isPlaying = true
                }
                setOnCompletionListener {
                    isPlaying = false
                    position = duration
                }
                setOnErrorListener { _, _, _ ->
                    failed = true
                    true
                }
                prepareAsync()
            }
        }.getOrElse {
            Log.w("MediaViewer", "Could not open audio: ${it.message}")
            null
        }
        if (mp == null) failed = true
        player = mp
        onDispose {
            mp?.runCatching { stop() }
            mp?.release()
            player = null
        }
    }

    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            player?.let { position = runCatching { it.currentPosition.toLong() }.getOrDefault(position) }
            delay(400)
        }
    }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            player?.pause()
            isPlaying = false
        }
    }

    if (failed) {
        PlaybackFailure(
            message = "This audio could not be played.",
            onOpenWith = onOpenWith
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val artBitmap = art
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF1E1E26)),
            contentAlignment = Alignment.Center
        ) {
            if (artBitmap != null) {
                Image(
                    bitmap = artBitmap.asImageBitmap(),
                    contentDescription = item.album ?: item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(28.dp))
                )
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFFB4B4C0),
                    modifier = Modifier.size(72.dp)
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Text(
            item.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = listOfNotNull(
            item.artist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) },
            item.album?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
        ).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(20.dp))

        val currentPosition = scrubPosition ?: position.coerceAtMost(duration).toFloat()
        Slider(
            value = currentPosition,
            onValueChange = { scrubPosition = it },
            onValueChangeFinished = {
                scrubPosition?.let { value ->
                    player?.seekTo(value.toInt())
                    position = value.toLong()
                }
                scrubPosition = null
            },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                formatDuration(scrubPosition?.toLong() ?: position),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        Spacer(Modifier.height(12.dp))

        if (!prepared && !failed) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        val mp = player ?: return@clickable
                        if (isPlaying) {
                            mp.pause()
                            isPlaying = false
                        } else {
                            // Replay from the start once finished.
                            if (duration > 0 && position >= duration - 500) {
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
                    if (isPlaying) {
                        Icons.Filled.Pause
                    } else {
                        Icons.Filled.PlayArrow
                    },
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------- shared bits

@Composable
private fun PlaybackFailure(message: String, onOpenWith: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenWith) {
            Text("Try another app")
        }
    }
}

@Composable
private fun MediaInfoDialog(item: MediaItem, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                InfoRow("Type", item.mimeType)
                InfoRow("Size", formatSize(item.sizeBytes))
                InfoRow("Added", formatDate(item.dateAdded))
                if (item.bucket != null) InfoRow("Folder", item.bucket)
                if (item.width != null && item.height != null) {
                    InfoRow("Dimensions", "${item.width} × ${item.height}")
                }
                item.durationMs?.let { InfoRow("Duration", formatDuration(it)) }
                if (item.kind == MediaKind.AUDIO) {
                    item.artist?.takeIf { it.isNotBlank() }?.let { InfoRow("Artist", it) }
                    item.album?.takeIf { it.isNotBlank() }?.let { InfoRow("Album", it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Hands a media file to whatever app can open it; null on success, a message on failure. */
private fun openWith(context: Context, item: MediaItem): String? {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, "media", item.uri)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(view, "Open with"))
        null
    }.getOrDefault("No app on this device can open that file.")
}

private fun shareMedia(context: Context, item: MediaItem) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, "media", item.uri)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share"))
    }
}
