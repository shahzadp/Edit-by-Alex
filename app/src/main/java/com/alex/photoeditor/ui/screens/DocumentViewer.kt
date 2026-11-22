package com.alex.photoeditor.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.MediaItem
import com.alex.photoeditor.ui.components.NamedActionIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
private const val MAX_TEXT_DISPLAY_CHARS = 60_000
private const val MAX_PAGE_EDGE = 4096
private const val HIGHLIGHT_PREFS = "pdf_highlights_v1"

@Serializable
private data class HighlightMap(val pages: Map<String, List<List<Float>>> = emptyMap())

/** Yellow marks stored per document page, in normalized page coordinates. */
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

/** Clear two-tone block eraser icon. */
private val EraserIcon: ImageVector = ImageVector.Builder(
    name = "Eraser",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color(0xFFE57373))) {
        moveTo(8.9f, 3.2f); lineTo(20.8f, 15.1f); lineTo(15.1f, 20.8f); lineTo(3.2f, 8.9f); close()
    }
    path(fill = SolidColor(Color.White)) {
        moveTo(3.2f, 8.9f); lineTo(8.9f, 14.6f); lineTo(15.1f, 20.8f)
        lineTo(9.4f, 20.8f); lineTo(3.2f, 14.6f); close()
    }
    path(fill = SolidColor(Color(0xFF333333))) {
        moveTo(8.0f, 6.6f); lineTo(17.4f, 16.0f); lineTo(15.1f, 18.3f); lineTo(5.7f, 8.9f); close()
    }
}.build()

/**
 * Finds text lines inside a dragged rectangle by detecting dark pixels in the
 * rendered page bitmap, producing tight per-line bands (no OCR needed).
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

internal fun isViewableDocument(item: MediaItem): Boolean = isPdfDocument(item) || isTextDocument(item)

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
 * Fullscreen, read-only document viewer: paginated PDFs with a yellow marker,
 * plus plain text / CSV. The marker flattens into a saved PDF on "Save".
 */
@Composable
fun DocumentViewer(item: MediaItem, onClose: () -> Unit) {
    BackHandler(enabled = true, onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            isPdfDocument(item) -> PdfView(item)
            isTextDocument(item) -> TextView(item)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            NamedActionIconButton(
                name = "Close",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onClose,
                tint = Color.White
            )
        }
    }
}

@Composable
private fun PdfView(item: MediaItem) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pageCount by produceState(initialValue = -1, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(item.uri, "r")?.use { fd ->
                    PdfRenderer(fd).use { it.pageCount }
                }
            }.getOrNull() ?: -1
        }
    }
    var pageIndex by remember(item.uri) { mutableStateOf(0) }
    var editingPage by remember(item.uri) { mutableStateOf(false) }
    var pageField by remember(item.uri) { mutableStateOf(TextFieldValue("", TextRange.Zero)) }
    val pageFocus = remember { FocusRequester() }

    val documentKey = item.absolutePath ?: item.uri.toString()
    val highlightKey = HighlightStore.key(documentKey, pageIndex)
    var highlightMode by remember(item.uri) { mutableStateOf(false) }
    var eraserMode by remember(item.uri) { mutableStateOf(false) }
    var allHighlights by remember(item.uri) { mutableStateOf(HighlightStore.load(context)) }
    var dragRect by remember(item.uri, pageIndex) { mutableStateOf<List<Float>?>(null) }
    val pageHighlights = allHighlights[highlightKey].orEmpty()

    val latestPageHighlights by rememberUpdatedState(pageHighlights)
    val latestAllHighlights by rememberUpdatedState(allHighlights)
    val latestHighlightKey by rememberUpdatedState(highlightKey)
    val latestEraser by rememberUpdatedState(eraserMode)

    val screenPx = LocalDensity.current.density * LocalConfiguration.current.screenWidthDp
    val targetWidth = (screenPx * 1.6f).toInt().coerceIn(1200, 2400)

    val bitmap by produceState<Bitmap?>(initialValue = null, item.uri, pageIndex, targetWidth) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(item.uri, "r")?.use { fd: ParcelFileDescriptor ->
                    PdfRenderer(fd).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return@use null
                        renderer.openPage(pageIndex).use { page ->
                            val height = (targetWidth.toFloat() * page.height / page.width)
                                .toInt().coerceIn(1, MAX_PAGE_EDGE)
                            val bmp = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
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
        eraserMode = false
        editingPage = false
    }

    fun normalize(point: Offset, bounds: androidx.compose.ui.unit.IntSize, bmp: Bitmap): List<Float> {
        // Undo the graphicsLayer transform (scale around center + translation),
        // then map through the ContentScale.Fit letterbox rect.
        val cx = bounds.width / 2f
        val cy = bounds.height / 2f
        val ux = cx + (point.x - offset.x - cx) / scale
        val uy = cy + (point.y - offset.y - cy) / scale
        val fit = minOf(bounds.width.toFloat() / bmp.width, bounds.height.toFloat() / bmp.height)
        val w = bmp.width * fit
        val h = bmp.height * fit
        val ox = (bounds.width - w) / 2f
        val oy = (bounds.height - h) / 2f
        return listOf(
            ((ux - ox) / w).coerceIn(0f, 1f),
            ((uy - oy) / h).coerceIn(0f, 1f)
        )
    }

    fun eraseAt(point: List<Float>) {
        val current = latestPageHighlights
        // A highlight band is thin; match any band whose horizontal span
        // contains the finger and whose vertical span is close enough.
        val kept = current.filterNot { r ->
            point[0] >= r[0] - 0.02f && point[0] <= r[2] + 0.02f &&
                point[1] >= r[1] - 0.02f && point[1] <= r[3] + 0.02f
        }
        if (kept.size != current.size) {
            val updated = allHighlights.toMutableMap()
            updated[highlightKey] = kept
            allHighlights = updated
            HighlightStore.save(context, updated)
        }
    }

    val interaction = if (highlightMode) {
        var toolModifier: Modifier = Modifier
        if (eraserMode) {
            toolModifier = toolModifier.pointerInput(item.uri, pageIndex) {
                detectDragGestures(
                    onDragStart = { start ->
                        bitmap?.let { bmp -> eraseAt(normalize(start, size, bmp)) }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        bitmap?.let { bmp -> eraseAt(normalize(change.position, size, bmp)) }
                    }
                )
            }
            toolModifier = toolModifier.pointerInput(item.uri, pageIndex) {
                detectTapGestures(onTap = { tap ->
                    bitmap?.let { bmp -> eraseAt(normalize(tap, size, bmp)) }
                })
            }
        } else {
            toolModifier = toolModifier.pointerInput(item.uri, pageIndex) {
                var activeDrag: List<Float>? = null
                detectDragGestures(
                    onDragStart = { start ->
                        bitmap?.let { bmp ->
                            val p = normalize(start, size, bmp)
                            activeDrag = listOf(p[0], p[1], p[0], p[1])
                            dragRect = activeDrag
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        bitmap?.let { bmp ->
                            val p = normalize(change.position, size, bmp)
                            activeDrag?.let { r ->
                                activeDrag = listOf(r[0], r[1], p[0], p[1])
                                dragRect = activeDrag
                            }
                        }
                    },
                    onDragEnd = {
                        activeDrag?.let { r ->
                            bitmap?.let { bmp ->
                                val ordered = listOf(
                                    minOf(r[0], r[2]), minOf(r[1], r[3]),
                                    maxOf(r[0], r[2]), maxOf(r[1], r[3])
                                )
                                val bands = findTextBands(bmp, ordered)
                                if (bands.isNotEmpty()) {
                                    val updated = latestAllHighlights.toMutableMap()
                                    val key = latestHighlightKey
                                    updated[key] = updated[key].orEmpty() + bands
                                    allHighlights = updated
                                    HighlightStore.save(context, updated)
                                }
                            }
                        }
                        activeDrag = null
                        dragRect = null
                    },
                    onDragCancel = { activeDrag = null; dragRect = null }
                )
            }
        }
        toolModifier.pointerInput(item.uri, pageIndex) {
            detectTapGestures(onDoubleTap = {
                if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
            })
        }
    } else {
        Modifier
            .transformable(transformState)
            .pointerInput(item.uri, pageIndex) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Box(modifier = Modifier.matchParentSize().then(interaction), contentAlignment = Alignment.Center) {
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

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
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
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.8f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolButton("Highlight", Icons.Filled.Highlight,
                    if (!eraserMode) Color(0xFFFFD54F) else Color.White) { eraserMode = false }
                ToolButton("Eraser", EraserIcon,
                    if (eraserMode) Color(0xFFFFD54F) else Color.White) { eraserMode = true }
                ToolButton("Clear", Icons.Filled.DeleteOutline, Color.White) {
                    val updated = allHighlights.toMutableMap()
                    updated.remove(highlightKey)
                    allHighlights = updated
                    HighlightStore.save(context, updated)
                }
                ToolButton("Save", Icons.Filled.Save, Color.White) {
                    scope.launch(Dispatchers.IO) {
                        val saved = saveHighlights(context, item, pageCount, targetWidth, allHighlights, documentKey)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                context,
                                if (saved != null) "Saved to $saved" else "Could not save the PDF",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                ToolButton("Zoom out", Icons.Filled.Remove, Color.White) {
                    scale = (scale / 1.3f).coerceIn(1f, 6f)
                    if (scale == 1f) offset = Offset.Zero
                }
                ToolButton("Zoom in", Icons.Filled.Add, Color.White) {
                    scale = (scale * 1.3f).coerceIn(1f, 6f)
                }
                ToolButton("Done", Icons.Filled.Check, Color.White) {
                    highlightMode = false
                    eraserMode = false
                    dragRect = null
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                NamedActionIconButton(
                    name = "Open highlight tools",
                    icon = Icons.Filled.Edit,
                    onClick = { highlightMode = true },
                    tint = Color(0xFF9E9E9E)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) }, enabled = pageIndex > 0) {
                Text("‹", color = Color.White)
            }
            if (editingPage) {
                LaunchedEffect(Unit) { pageFocus.requestFocus() }
                OutlinedTextField(
                    value = pageField,
                    onValueChange = { value ->
                        if (value.text.all { it.isDigit() }) pageField = value
                    },
                    singleLine = true,
                    modifier = Modifier.width(104.dp).height(50.dp).focusRequester(pageFocus),
                    textStyle = MaterialTheme.typography.labelMedium.copy(
                        color = Color.White,
                        textAlign = TextAlign.Right
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        pageField.text.toIntOrNull()?.let { requested ->
                            if (requested in 1..pageCount) pageIndex = requested - 1
                        }
                        editingPage = false
                    })
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
                    Text("${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
            TextButton(
                onClick = { pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1) },
                enabled = pageCount > 0 && pageIndex < pageCount - 1
            ) {
                Text("›", color = Color.White)
            }
        }
    }
}

/** Flattens the yellow marks into a new PDF saved in Downloads. */
private fun saveHighlights(
    context: Context,
    item: MediaItem,
    pageCount: Int,
    width: Int,
    highlights: Map<String, List<List<Float>>>,
    documentKey: String
): String? = runCatching {
    val document = PdfDocument()
    for (i in 0 until pageCount) {
        val source = context.contentResolver.openFileDescriptor(item.uri, "r") ?: continue
        val bitmap = source.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (i !in 0 until renderer.pageCount) return@use null
                renderer.openPage(i).use { page ->
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, MAX_PAGE_EDGE)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        } ?: continue
        val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, i + 1).create()
        val page = document.startPage(info)
        val canvas = page.canvas
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        val markPaint = Paint().apply {
            color = 0xFFFFD54F.toInt()
            alpha = 110
            style = Paint.Style.FILL
        }
        highlights[HighlightStore.key(documentKey, i)].orEmpty().forEach { r ->
            canvas.drawRect(
                r[0] * bitmap.width, r[1] * bitmap.height,
                r[2] * bitmap.width, r[3] * bitmap.height, markPaint
            )
        }
        document.finishPage(page)
    }
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Edit by Alex Edited"
    )
    if (!dir.exists() && !dir.mkdirs()) return@runCatching null
    val base = item.name.substringBeforeLast('.', item.name).ifBlank { "document" }
    val output = File(dir, "$base-marked.pdf")
    FileOutputStream(output).use { document.writeTo(it) }
    document.close()
    android.media.MediaScannerConnection.scanFile(context, arrayOf(output.absolutePath), arrayOf("application/pdf"), null)
    output.absolutePath
}.getOrNull()

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ToolButton(name: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    val state = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(name) } },
        state = state
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
            Icon(icon, contentDescription = name, tint = tint, modifier = Modifier.size(20.dp))
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
        null -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                "Opening ${item.name}…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Reading file contents",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            Text(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
                text = r.text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (r.truncated) {
                Text("Large file — showing the first part. Open in another app to see the whole file.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

private data class TextResult(val text: String, val truncated: Boolean)

private fun readText(context: Context, item: MediaItem): TextResult = runCatching {
    val stream = context.contentResolver.openInputStream(item.uri)
        ?: return@runCatching TextResult("Could not open this file.", false)
    stream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var clipped = false
        var zeroReads = 0
        // Only read what we can show — stop as soon as the buffer has enough.
        val readLimit = MAX_TEXT_DISPLAY_CHARS * 2
        while (out.size() < readLimit) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) { if (++zeroReads > 64) break; continue }
            zeroReads = 0
            out.write(buffer, 0, read)
        }
        if (out.size() >= readLimit) clipped = true
        val fullText = String(out.toByteArray(), Charsets.UTF_8)
        val text = fullText.take(MAX_TEXT_DISPLAY_CHARS)
        if (text.length >= MAX_TEXT_DISPLAY_CHARS) clipped = true
        TextResult(
            if (text.isNotBlank()) text else "(empty file)",
            clipped
        )
    }
}.getOrElse { error -> TextResult("Could not read this file: ${error.message ?: "unknown error"}", false) }
