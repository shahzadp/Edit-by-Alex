package com.alex.photoeditor.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.MediaItem
import com.alex.photoeditor.core.MediaStoreRepository
import com.alex.photoeditor.core.XlsxDocument
import com.alex.photoeditor.core.XlsxReader
import com.alex.photoeditor.ui.components.NamedActionIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
private const val MAX_PAGE_EDGE = 4096
private const val HIGHLIGHT_PREFS = "pdf_highlights_v1"

/**
 * Detects text lines inside a dragged rectangle by looking for dark pixels in
 * the already-rendered page bitmap. Returns tight, per-line bands so the mark
 * follows the text instead of covering a random big square. No OCR/model is
 * needed and it works for any script.
 */
private fun findTextBands(bitmap: Bitmap, drag: List<Float>): List<List<Float>> {
    val rect = listOf(
        minOf(drag[0], drag[2]), minOf(drag[1], drag[3]),
        maxOf(drag[0], drag[2]), maxOf(drag[1], drag[3])
    )
    val x0 = (rect[0] * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
    val x1 = (rect[2] * bitmap.width).toInt().coerceIn(x0 + 1, bitmap.width)
    val y0 = (rect[1] * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
    val y1 = (rect[3] * bitmap.height).toInt().coerceIn(y0 + 1, bitmap.height)
    val rows = y1 - y0
    val darkEnough = BooleanArray(rows)
    val minX = IntArray(rows) { Int.MAX_VALUE }
    val maxX = IntArray(rows) { -1 }
    var y = y0
    while (y < y1) {
        var count = 0
        var left = Int.MAX_VALUE
        var right = -1
        var x = x0
        while (x < x1) {
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r + g + b < 480) {
                count++
                if (x < left) left = x
                if (x > right) right = x
            }
            x += 2
        }
        if (count >= 2) {
            val row = y - y0
            darkEnough[row] = true
            minX[row] = left
            maxX[row] = right
        }
        y++
    }

    val bands = ArrayList<List<Float>>()
    var start = -1
    var end = -1
    var bandLeft = Int.MAX_VALUE
    var bandRight = -1
    for (row in 0 until rows) {
        if (darkEnough[row]) {
            if (start < 0) start = row
            end = row
            bandLeft = minOf(bandLeft, minX[row])
            bandRight = maxOf(bandRight, maxX[row])
        } else if (start >= 0 && row - end > 3) {
            emitBand(bands, bitmap, y0 + start, y0 + end, bandLeft, bandRight)
            start = -1
            bandLeft = Int.MAX_VALUE
            bandRight = -1
        }
    }
    if (start >= 0) emitBand(bands, bitmap, y0 + start, y0 + end, bandLeft, bandRight)
    return bands
}

private fun emitBand(
    bands: ArrayList<List<Float>>,
    bitmap: Bitmap,
    start: Int,
    end: Int,
    left: Int,
    right: Int
) {
    if (right < left) return
    val padX = 3f / bitmap.width
    val padY = 2f / bitmap.height
    bands.add(
        listOf(
            (left.toFloat() / bitmap.width - padX).coerceIn(0f, 1f),
            (start.toFloat() / bitmap.height - padY).coerceIn(0f, 1f),
            (right.toFloat() / bitmap.width + padX).coerceIn(0f, 1f),
            ((end + 1).toFloat() / bitmap.height + padY).coerceIn(0f, 1f)
        )
    )
}

@Serializable
private data class HighlightMap(val pages: Map<String, List<List<Float>>> = emptyMap())

/** Yellow marks are stored per document page, in normalized 0..1 coordinates. */
private object HighlightStore {
    fun load(context: Context): Map<String, List<List<Float>>> = runCatching {
        val json = context.getSharedPreferences(HIGHLIGHT_PREFS, Context.MODE_PRIVATE)
            .getString("data", null) ?: return emptyMap()
        Json.decodeFromString<HighlightMap>(json).pages
    }.getOrDefault(emptyMap())

    fun save(context: Context, pages: Map<String, List<List<Float>>>) {
        runCatching {
            context.getSharedPreferences(HIGHLIGHT_PREFS, Context.MODE_PRIVATE)
                .edit().putString("data", Json.encodeToString(HighlightMap(pages))).apply()
        }
    }

    fun key(document: String, page: Int) = "$document#$page"
}

/** Documents this app can render itself, read-only. */
internal fun isViewableDocument(item: MediaItem): Boolean =
    item.kind == com.alex.photoeditor.core.MediaKind.DOCUMENT &&
        (isPdfDocument(item) || isTextDocument(item) || isXlsxDocument(item))

private fun isXlsxDocument(item: MediaItem): Boolean =
    item.mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
        item.name.endsWith(".xlsx", ignoreCase = true)

private fun isPdfDocument(item: MediaItem): Boolean =
    item.mimeType.equals("application/pdf", ignoreCase = true) ||
        item.name.endsWith(".pdf", ignoreCase = true)

private fun isTextDocument(item: MediaItem): Boolean {
    val ext = item.name.substringAfterLast('.', "").lowercase()
    return item.mimeType.startsWith("text/", ignoreCase = true) ||
        item.mimeType in setOf("application/json", "application/xml", "application/x-yaml") ||
        ext in setOf("txt", "csv", "md", "json", "xml", "yaml", "yml", "log", "ini", "tex", "rtf")
}

/**
 * Tier 0 document viewing inside the app: paginated PDF rendering and plain
 * text / CSV. The viewer is fullscreen with only a small back control — no
 * external-editor escape hatch. Editing is out of scope at this tier.
 */
@Composable
fun DocumentViewer(
    item: MediaItem,
    onClose: () -> Unit
) {
    BackHandler(enabled = true, onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            isPdfDocument(item) -> PdfView(item)
            isXlsxDocument(item) -> XlsxView(item)
            isTextDocument(item) -> TextView(item)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            NamedActionIconButton(
                name = "Close",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onClose,
                tint = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}

@Composable
private fun PdfView(item: MediaItem) {
    val context = LocalContext.current
    val pageCount by produceState(initialValue = -1, item.uri) {
        value = runCatching {
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
        }.getOrNull() ?: -1
    }
    var pageIndex by remember(item.uri) { mutableStateOf(0) }
    var editingPage by remember(item.uri) { mutableStateOf(false) }
    var pageField by remember(item.uri) {
        mutableStateOf(TextFieldValue("", TextRange.Zero))
    }
    val pageFocus = remember { FocusRequester() }
    val documentKey = item.absolutePath ?: item.uri.toString()
    val highlightKey = HighlightStore.key(documentKey, pageIndex)
    var highlightMode by remember(item.uri) { mutableStateOf(false) }
    var eraserMode by remember(item.uri) { mutableStateOf(false) }
    var allHighlights by remember(item.uri) { mutableStateOf(HighlightStore.load(context)) }
    var dragRect by remember(item.uri, pageIndex) { mutableStateOf<List<Float>?>(null) }
    val pageHighlights = allHighlights[highlightKey].orEmpty()
    // Gesture callbacks outlive recompositions; read the latest values so a
    // second stroke on the same page builds on the first one instead of a
    // stale snapshot of the highlight map.
    val latestHighlights by rememberUpdatedState(allHighlights)
    val latestPageHighlights by rememberUpdatedState(pageHighlights)
    val latestEraser by rememberUpdatedState(eraserMode)
    // Render well above screen resolution; the viewer scales it down, so
    // small document text stays sharp when zoomed in.
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val targetWidth = (screenPx * 1.6f).toInt().coerceIn(1200, 2400)

    val bitmap by produceState<Bitmap?>(initialValue = null, item.uri, pageIndex, targetWidth) {
        value = withContext(Dispatchers.IO) {
            if (pageCount <= 0) return@withContext null
            runCatching {
                context.contentResolver.openFileDescriptor(item.uri, "r")?.use { fd: ParcelFileDescriptor ->
                    PdfRenderer(fd).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return@use null
                        renderer.openPage(pageIndex).use { page ->
                            val height = (targetWidth.toFloat() * page.height / page.width)
                                .toInt().coerceIn(1, MAX_PAGE_EDGE)
                            val bmp = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                            // PDF pages are transparent where nothing is drawn;
                            // without a white fill the dark app background shows
                            // through and black text becomes unreadable.
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                }
            }.getOrNull()
        }
    }

    var scale by remember(item.uri, pageIndex) { mutableStateOf(1f) }
    var offset by remember(item.uri, pageIndex) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + pan * scale else Offset.Zero
    }
    LaunchedEffect(item.uri, pageIndex) {
        scale = 1f
        offset = Offset.Zero
        dragRect = null
        highlightMode = false
    }

    fun normalize(point: Offset, bounds: androidx.compose.ui.unit.IntSize, bmp: Bitmap): List<Float> {
        val fit = minOf(bounds.width.toFloat() / bmp.width, bounds.height.toFloat() / bmp.height)
        val w = bmp.width * fit
        val h = bmp.height * fit
        val ox = (bounds.width - w) / 2f
        val oy = (bounds.height - h) / 2f
        return listOf(
            ((point.x - ox) / w).coerceIn(0f, 1f),
            ((point.y - oy) / h).coerceIn(0f, 1f)
        )
    }

    fun eraseAt(point: List<Float>) {
        val current = latestPageHighlights
        val kept = current.filterNot { r ->
            point[0] >= r[0] - 0.006f && point[0] <= r[2] + 0.006f &&
                point[1] >= r[1] - 0.006f && point[1] <= r[3] + 0.006f
        }
        if (kept.size != current.size) {
            val updated = latestHighlights.toMutableMap()
            updated[highlightKey] = kept
            allHighlights = updated
            HighlightStore.save(context, updated)
        }
    }

    val interaction = if (highlightMode) {
        Modifier
            .pointerInput(item.uri, pageIndex, eraserMode) {
                var activeDrag: List<Float>? = null
                detectDragGestures(
                    onDragStart = { start ->
                        bitmap?.let { bmp ->
                            val p = normalize(start, size, bmp)
                            if (latestEraser) {
                                eraseAt(p)
                            } else {
                                activeDrag = listOf(p[0], p[1], p[0], p[1])
                                dragRect = activeDrag
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        bitmap?.let { bmp ->
                            val p = normalize(change.position, size, bmp)
                            if (latestEraser) {
                                eraseAt(p)
                            } else {
                                activeDrag?.let { r ->
                                    activeDrag = listOf(r[0], r[1], p[0], p[1])
                                    dragRect = activeDrag
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        if (!latestEraser) {
                            activeDrag?.let { r ->
                                bitmap?.let { bmp ->
                                    val ordered = listOf(
                                        minOf(r[0], r[2]), minOf(r[1], r[3]),
                                        maxOf(r[0], r[2]), maxOf(r[1], r[3])
                                    )
                                    val bands = findTextBands(bmp, ordered)
                                    if (bands.isNotEmpty()) {
                                        val updated = latestHighlights.toMutableMap()
                                        updated[highlightKey] = latestPageHighlights + bands
                                        allHighlights = updated
                                        HighlightStore.save(context, updated)
                                    }
                                }
                            }
                        }
                        activeDrag = null
                        dragRect = null
                    },
                    onDragCancel = {
                        activeDrag = null
                        dragRect = null
                    }
                )
            }
            .pointerInput(item.uri, pageIndex, eraserMode) {
                detectTapGestures(onTap = { tap ->
                    if (latestEraser) bitmap?.let { bmp -> eraseAt(normalize(tap, size, bmp)) }
                })
            }
    } else {
        Modifier
            .transformable(transformState)
            .pointerInput(item.uri, pageIndex) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.White)
    ) {
        Box(
            modifier = Modifier.matchParentSize().then(interaction),
            contentAlignment = Alignment.Center
        ) {
            when {
                pageCount <= 0 -> Text(
                    "This PDF could not be opened.",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
                bitmap == null -> CircularProgressIndicator()
                else -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
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

        // Yellow marks drawn over the rendered page, in normalized coordinates
        // so they stay aligned with the page when the screen rotates.
        Canvas(modifier = Modifier.matchParentSize()) {
            val bmp = bitmap ?: return@Canvas
            val fit = minOf(size.width / bmp.width.toFloat(), size.height / bmp.height.toFloat())
            val w = bmp.width * fit
            val h = bmp.height * fit
            val ox = (size.width - w) / 2f
            val oy = (size.height - h) / 2f
            val yellow = Color(0xFFFFD54F).copy(alpha = 0.42f)
            fun drawMark(r: List<Float>) {
                drawRect(
                    color = yellow,
                    topLeft = Offset(ox + r[0] * w, oy + r[1] * h),
                    size = Size((r[2] - r[0]) * w, (r[3] - r[1]) * h)
                )
            }
            pageHighlights.forEach(::drawMark)
            dragRect?.let(::drawMark)
        }

        if (highlightMode) {
            // Tool strip stays open so several highlights can be made in one
            // pass; Highlight/Erase/Clear/Done are all reachable here.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 66.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { eraserMode = false }) {
                    Text(
                        "Highlight",
                        color = if (!eraserMode) Color(0xFFFFD54F) else androidx.compose.ui.graphics.Color.White
                    )
                }
                TextButton(onClick = { eraserMode = true }) {
                    Text(
                        "Eraser",
                        color = if (eraserMode) Color(0xFFFFD54F) else androidx.compose.ui.graphics.Color.White
                    )
                }
                TextButton(onClick = {
                    val updated = allHighlights.toMutableMap()
                    updated.remove(highlightKey)
                    allHighlights = updated
                    HighlightStore.save(context, updated)
                }) { Text("Clear", color = androidx.compose.ui.graphics.Color.White) }
                TextButton(onClick = {
                    highlightMode = false
                    eraserMode = false
                    dragRect = null
                }) { Text("Done", color = androidx.compose.ui.graphics.Color.White) }
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                NamedActionIconButton(
                    name = "Open highlight tools",
                    icon = Icons.Filled.Edit,
                    onClick = {
                        highlightMode = true
                        scale = 1f
                        offset = Offset.Zero
                    },
                    tint = Color(0xFFFFD54F)
                )
            }
        }

        // Compact floating navigation: the page keeps the full screen height.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) }, enabled = pageIndex > 0) {
                Text("‹", color = androidx.compose.ui.graphics.Color.White)
            }
            if (editingPage) {
                LaunchedEffect(Unit) { pageFocus.requestFocus() }
                OutlinedTextField(
                    value = pageField,
                    onValueChange = { value ->
                        if (value.text.all { it.isDigit() }) pageField = value
                    },
                    singleLine = true,
                    modifier = Modifier
                        .width(104.dp)
                        .height(50.dp)
                        .focusRequester(pageFocus),
                    textStyle = MaterialTheme.typography.labelMedium.copy(
                        color = androidx.compose.ui.graphics.Color.White,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Right
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            pageField.text.toIntOrNull()?.let { requested ->
                                if (requested in 1..pageCount) pageIndex = requested - 1
                            }
                            editingPage = false
                        }
                    )
                )
            } else {
                TextButton(
                    onClick = {
                        val current = (pageIndex + 1).toString()
                        pageField = TextFieldValue(current, TextRange(0, current.length))
                        editingPage = true
                    },
                    enabled = pageCount > 0
                ) {
                    Text(
                        "${pageIndex + 1} / $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
            TextButton(
                onClick = { pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1) },
                enabled = pageCount > 0 && pageIndex < pageCount - 1
            ) {
                Text("›", color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

@Composable
private fun XlsxView(item: MediaItem) {
    val context = LocalContext.current
    val doc by produceState<XlsxDocument?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) {
            val path = item.absolutePath ?: MediaStoreRepository.filePathFor(context, item)
            path?.let { XlsxReader.read(it) }
        }
    }
    var sheetIndex by remember(item.uri) { mutableStateOf(0) }
    val document = doc
    when {
        document == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        document.sheets.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("This spreadsheet could not be opened.", color = MaterialTheme.colorScheme.error)
        }
        else -> {
            val sheet = document.sheets[sheetIndex.coerceIn(document.sheets.indices)]
            Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    document.sheets.forEachIndexed { index, s ->
                        TextButton(
                            onClick = { sheetIndex = index },
                            enabled = s.name != sheet.name
                        ) { Text(s.name) }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        sheet.rows.forEachIndexed { rowIndex, row ->
                            Row {
                                row.forEach { value ->
                                    Box(
                                        modifier = Modifier
                                            .width(112.dp)
                                            .height(34.dp)
                                            .border(
                                                0.5.dp,
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            )
                                            .background(
                                                if (rowIndex == 0) {
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surface
                                                }
                                            )
                                            .padding(horizontal = 4.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(
                                            value,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextView(item: MediaItem) {
    val context = LocalContext.current
    val result by produceState<TextResult?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) { readText(context, item) }
    }
    when (val r = result) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                text = r.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (r.truncated) {
                Text(
                    "Showing the first 2 MB — open in another app to see the whole file.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private data class TextResult(val text: String, val truncated: Boolean)

private fun readText(context: Context, item: MediaItem): TextResult = runCatching {
    val stream = context.contentResolver.openInputStream(item.uri)
        ?: return@runCatching TextResult(
            "Could not open this file. Use “Open with” in the top bar.",
            false
        )
    stream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var truncated = false
        var zeroReads = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            // Some provider streams return 0 temporarily; without this guard
            // the loop spins forever and the viewer looks stuck.
            if (read == 0) {
                if (++zeroReads > 64) break
                continue
            }
            zeroReads = 0
            if (out.size() + read > MAX_TEXT_BYTES) {
                truncated = true
                break
            }
            out.write(buffer, 0, read)
        }
        val text = String(out.toByteArray(), Charsets.UTF_8)
        TextResult(if (text.isNotBlank()) text else "(empty file)", truncated)
    }
}.getOrElse { error ->
    TextResult("Could not read this file: ${error.message ?: "unknown error"}", false)
}
