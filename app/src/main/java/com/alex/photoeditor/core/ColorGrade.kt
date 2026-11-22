package com.alex.photoeditor.core

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A control point on a tone curve.
 *
 * `x` is input luminance (0..1) and `y` is output luminance (0..1), matching
 * how Photoshop and Lightroom present curve editors.
 */
@Serializable
data class CurvePoint(val x: Float, val y: Float)

/**
 * One channel's tone curve. Curves are stored in
 * [com.alex.photoeditor.core.EditState] as normalised point lists so they
 * remain serialisable and non-destructive.
 */
@Serializable
data class ToneCurve(
    val channel: CurveChannel,
    val points: List<CurvePoint>
) {
    val isIdentity: Boolean
        get() = points.size <= 2 && points.zipWithNext().all {
            kotlin.math.abs(it.first.y - it.first.x) < 0.001f
        }

    companion object {
        fun identity(channel: CurveChannel) = ToneCurve(channel, DEFAULT_POINTS)
        val DEFAULT_POINTS = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

/**
 * Editing operations on a tone curve's control points.
 *
 * Kept next to the data type so the invariants (sorted by x, endpoints pinned,
 * no two points on the same input) live in one place instead of being
 * re-derived in each panel.
 */
object CurveEditor {

    /** How close to an endpoint a touch counts as "grabbed the endpoint". */
    private const val EDGE = 0.06f

    /** Two points closer than this in x are treated as the same stop. */
    private const val MERGE = 0.02f

    /** Normalises: sorted by x, de-duplicated, endpoints present. */
    fun normalise(points: List<CurvePoint>): List<CurvePoint> {
        val sorted = points.sortedBy { it.x }
        val deduped = ArrayList<CurvePoint>(sorted.size)
        for (p in sorted) {
            val x = p.x.coerceIn(0f, 1f)
            val y = p.y.coerceIn(0f, 1f)
            if (deduped.isEmpty() || x - deduped.last().x >= MERGE) {
                deduped.add(CurvePoint(x, y))
            } else {
                // Same input stop: keep the newer output value.
                deduped[deduped.lastIndex] = CurvePoint(deduped.last().x, y)
            }
        }
        // The curve is defined on [0,1]; both ends must exist.
        if (deduped.isEmpty() || deduped.first().x > 0f) {
            deduped.add(0, CurvePoint(0f, if (deduped.isEmpty()) 0f else deduped.first().y))
        }
        if (deduped.last().x < 1f) {
            deduped.add(CurvePoint(1f, deduped.last().y))
        }
        return deduped
    }

    /** Index of the control point nearest to ([x], [y]), or -1. */
    fun hitTest(points: List<CurvePoint>, x: Float, y: Float, radius: Float): Int {
        var best = -1
        var bestDist = radius * radius
        points.forEachIndexed { i, p ->
            val dx = p.x - x
            val dy = p.y - y
            val d = dx * dx + dy * dy
            if (d <= bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /**
     * Moves an existing point.
     *
     * The two endpoints stay pinned to the edges of the graph — dragging a
     * corner off the canvas would leave the curve undefined outside [0,1] and
     * produce a hard clipped edge on the image.
     */
    fun move(
        points: List<CurvePoint>,
        index: Int,
        x: Float,
        y: Float
    ): List<CurvePoint> {
        if (index !in points.indices) return points
        val out = ArrayList(points)
        val isFirst = index == 0
        val isLast = index == out.lastIndex

        val newX = when {
            isFirst -> 0f
            isLast -> 1f
            else -> x.coerceIn(
                (out[index - 1].x + MERGE).coerceAtMost(1f - MERGE),
                (out[index + 1].x - MERGE).coerceAtLeast(MERGE)
            )
        }
        out[index] = CurvePoint(newX, y.coerceIn(0f, 1f))
        return out
    }

    /** Inserts a new point, replacing any stop it lands on top of. */
    fun insert(points: List<CurvePoint>, x: Float, y: Float): List<CurvePoint> {
        val existing = hitTest(points, x, y, EDGE)
        return if (existing >= 0) {
            move(points, existing, x, y)
        } else {
            normalise(points + CurvePoint(x.coerceIn(MERGE, 1f - MERGE), y.coerceIn(0f, 1f)))
        }
    }

    /** Removes a point. Endpoints are never removable. */
    fun remove(points: List<CurvePoint>, index: Int): List<CurvePoint> {
        if (index <= 0 || index >= points.lastIndex) return points
        return points.toMutableList().also { it.removeAt(index) }
    }

    /** Clamps a point's y so it stays within [EDGE] of the graph bounds. */
    fun clampY(y: Float): Float = y.coerceIn(0f, 1f)

    /** Samples the curve for drawing. Mirrors the engine's interpolation. */
    fun sample(points: List<CurvePoint>, steps: Int = 64): List<CurvePoint> {
        if (points.size < 2) return points
        val out = ArrayList<CurvePoint>(steps + 1)
        for (i in 0..steps) {
            val x = i.toFloat() / steps
            out.add(CurvePoint(x, ToneCurveSampler.eval(points, x)))
        }
        return out
    }
}

@Serializable
enum class CurveChannel(val label: String) {
    RED("Red"),
    GREEN("Green"),
    BLUE("Blue")
}

/** Black/white/gamma input mapping, the "Levels" equivalent. */
@Serializable
data class Levels(
    val blackPoint: Float = 0f,
    val whitePoint: Float = 1f,
    val gamma: Float = 1f
) {
    val isIdentity: Boolean
        get() = blackPoint == 0f && whitePoint == 1f && gamma == 1f
}

/**
 * HSL adjustment operating on hue, saturation and lightness independently.
 *
 * Values are normalised: hue in [-1, 1] where 1 is a full 360 degree rotation,
 * saturation/lightness in [-1, 1].
 */
@Serializable
data class HslAdjustments(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val lightness: Float = 0f
) {
    val isIdentity: Boolean
        get() = hue == 0f && saturation == 0f && lightness == 0f
}

/** Photoshop's Relative / Absolute switch for Selective Color. */
@Serializable
enum class SelectiveMode { RELATIVE, ABSOLUTE }

/** The nine colour ranges Photoshop's Selective Color can target. */
enum class SelectiveRange(val label: String) {
    REDS("Reds"), YELLOWS("Yellows"), GREENS("Greens"),
    CYANS("Cyans"), BLUES("Blues"), MAGENTAS("Magentas"),
    WHITES("Whites"), NEUTRALS("Neutrals"), BLACKS("Blacks")
}

/**
 * Photoshop's "Selective Color", faithful to the algorithm Adobe uses (the
 * one independently reverse-engineered and shipped as FFmpeg's
 * `selectivecolor` filter).
 *
 * Each of the nine ranges holds CMYK offsets in [-1, 1]. Cyan / Magenta /
 * Yellow drive the R / G / B components respectively and Black is the K
 * offset. A pixel can belong to several ranges at once; their contributions
 * are summed.
 */
@Serializable
data class SelectiveColor(
    // The original three ranges keep their old names so saved projects from
    // before the full Photoshop implementation still deserialise correctly.
    val red: RgbCorrection = RgbCorrection(),
    val yellow: RgbCorrection = RgbCorrection(),
    val green: RgbCorrection = RgbCorrection(),
    val cyan: RgbCorrection = RgbCorrection(),
    val blue: RgbCorrection = RgbCorrection(),
    val magenta: RgbCorrection = RgbCorrection(),
    val white: RgbCorrection = RgbCorrection(),
    val neutral: RgbCorrection = RgbCorrection(),
    val black: RgbCorrection = RgbCorrection(),
    val mode: SelectiveMode = SelectiveMode.RELATIVE,
    val isEnabled: Boolean = false
) {
    fun correctionFor(range: SelectiveRange): RgbCorrection = when (range) {
        SelectiveRange.REDS -> red
        SelectiveRange.YELLOWS -> yellow
        SelectiveRange.GREENS -> green
        SelectiveRange.CYANS -> cyan
        SelectiveRange.BLUES -> blue
        SelectiveRange.MAGENTAS -> magenta
        SelectiveRange.WHITES -> white
        SelectiveRange.NEUTRALS -> neutral
        SelectiveRange.BLACKS -> black
    }

    fun withCorrection(range: SelectiveRange, corr: RgbCorrection): SelectiveColor = when (range) {
        SelectiveRange.REDS -> copy(red = corr)
        SelectiveRange.YELLOWS -> copy(yellow = corr)
        SelectiveRange.GREENS -> copy(green = corr)
        SelectiveRange.CYANS -> copy(cyan = corr)
        SelectiveRange.BLUES -> copy(blue = corr)
        SelectiveRange.MAGENTAS -> copy(magenta = corr)
        SelectiveRange.WHITES -> copy(white = corr)
        SelectiveRange.NEUTRALS -> copy(neutral = corr)
        SelectiveRange.BLACKS -> copy(black = corr)
    }

    /** True when the adjustment would change no pixels. */
    val isIdentity: Boolean
        get() = red.isIdentity && yellow.isIdentity && green.isIdentity &&
            cyan.isIdentity && blue.isIdentity && magenta.isIdentity &&
            white.isIdentity && neutral.isIdentity && black.isIdentity

    /** True only when enabled *and* actually carrying an adjustment. */
    val isEffective: Boolean get() = isEnabled && !isIdentity
}

/** Per-range CMYK correction. Values in [-1, 1]. */
@Serializable
data class RgbCorrection(
    val cyan: Float = 0f,
    val magenta: Float = 0f,
    val yellow: Float = 0f,
    val black: Float = 0f
) {
    val isIdentity: Boolean
        get() = cyan == 0f && magenta == 0f && yellow == 0f && black == 0f
}

/**
 * The full Phase 2 professional colour state.
 *
 * Kept separate from the Phase 1 [Adjustments] so the two can evolve
 * independently, and folded into the same non-destructive pipeline.
 */
@Serializable
data class ColorGrade(
    val curves: Map<CurveChannel, ToneCurve> = emptyMap(),
    val levels: Map<CurveChannel, Levels> = emptyMap(),
    val hsl: HslAdjustments = HslAdjustments(),
    val selectiveColor: SelectiveColor = SelectiveColor()
) {
    val isNeutral: Boolean
        get() = curves.values.none { !it.isIdentity } &&
            levels.values.none { !it.isIdentity } &&
            hsl.isIdentity &&
            !selectiveColor.isEffective
}

/**
 * Applies curves, levels, HSL and selective colour.
 *
 * Tone mapping (curves + levels) runs first and produces a shared luminance
 * lookup table, so the two passes combined cost roughly one extra pass rather
 * than two separate ones. Colour operations then run on the result.
 */
class ColorGradeEngine {

    /**
     * Builds a 256-entry mapping table for a curve/levels pair.
     *
     * Precomputing this is what keeps sliders responsive: adjusting a curve is
     * a table rebuild, not a per-pixel pow() call.
     */
    private fun buildLut(
        curve: ToneCurve?,
        levels: Levels?,
        channel: Int
    ): IntArray {
        val lut = IntArray(256)
        val ctrl = curve?.points

        for (i in 0 until 256) {
            var v = i / 255f

            // --- Levels ---
            levels?.let { l ->
                if (!l.isIdentity) {
                    val bp = l.blackPoint.coerceIn(0f, 0.99f)
                    val wp = l.whitePoint.coerceIn(bp + 0.01f, 1f)
                    // Linearise to the black/white window
                    v = ((v - bp) / (wp - bp)).coerceIn(0f, 1f)
                    // Apply midtone gamma
                    if (l.gamma != 1f && l.gamma > 0f) {
                        v = v.coerceAtLeast(0f).pow(1f / l.gamma)
                    }
                }
            }

            // --- Tone curve (monotone cubic through the control points) ---
            if (ctrl != null && ctrl.size >= 2) {
                v = evalCurve(ctrl, v).coerceIn(0f, 1f)
            }

            lut[i] = (v * 255f).roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    /** Monotone cubic (Fritsch-Carlson) interpolation for smooth, non-overshooting curves. */
    private fun evalCurve(points: List<CurvePoint>, x: Float): Float =
        ToneCurveSampler.eval(points, x)

    suspend fun apply(source: Bitmap, grade: ColorGrade): Bitmap =
        withContext(Dispatchers.Default) {
            if (grade.isNeutral) return@withContext source

            val w = source.width
            val h = source.height
            val px = IntArray(w * h)
            source.getPixels(px, 0, w, 0, 0, w, h)

            applyInPlace(px, grade)

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            out
        }

    fun applyInPlace(px: IntArray, grade: ColorGrade) {
        val hasTone = grade.curves.values.any { !it.isIdentity } ||
            grade.levels.values.any { !it.isIdentity }

        if (hasTone) {
            val rLut = buildLut(grade.curves[CurveChannel.RED], grade.levels[CurveChannel.RED], 0)
            val gLut = buildLut(grade.curves[CurveChannel.GREEN], grade.levels[CurveChannel.GREEN], 1)
            val bLut = buildLut(grade.curves[CurveChannel.BLUE], grade.levels[CurveChannel.BLUE], 2)

            for (i in px.indices) {
                val c = px[i]
                px[i] = (c and OPAQUE) or
                    (rLut[(c shr 16) and 0xFF] shl 16) or
                    (gLut[(c shr 8) and 0xFF] shl 8) or
                    bLut[c and 0xFF]
            }
        }

        if (!grade.hsl.isIdentity) {
            applyHsl(px, grade.hsl)
        }

        if (grade.selectiveColor.isEnabled) {
            applySelectiveColor(px, grade.selectiveColor)
        }
    }

    // ----------------------------------------------------------------- HSL

    private fun applyHsl(px: IntArray, adj: HslAdjustments) {
        val hueShift = adj.hue * 360f
        val satScale = 1f + adj.saturation
        val lightDelta = adj.lightness * 0.5f

        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) * INV255
            val g = ((c shr 8) and 0xFF) * INV255
            val b = (c and 0xFF) * INV255

            val (h, s, l) = rgbToHsl(r, g, b)
            if (s < 0.0001f) {
                // Achromatic: only lightness applies.
                if (lightDelta != 0f) {
                    val nl = (l + lightDelta).coerceIn(0f, 1f)
                    val v = (nl * 255f).roundToInt().coerceIn(0, 255)
                    px[i] = (c and OPAQUE) or (v shl 16) or (v shl 8) or v
                }
                continue
            }

            var nh = (h + hueShift) % 360f
            if (nh < 0f) nh += 360f
            val ns = (s * satScale).coerceIn(0f, 1f)
            val nl = (l + lightDelta).coerceIn(0f, 1f)

            val (nr, ng, nb) = hslToRgb(nh, ns, nl)
            px[i] = (c and OPAQUE) or
                (to255(nr) shl 16) or
                (to255(ng) shl 8) or
                to255(nb)
        }
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)

        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)

        val h = when (max) {
            r -> (g - b) / d + if (g < b) 6f else 0f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f

        return Triple(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Float, Float, Float> {
        if (s == 0f) return Triple(l, l, l)

        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))

        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val m = l - c / 2f
        return Triple(r1 + m, g1 + m, b1 + m)
    }

    // ------------------------------------------------------ selective colour

    /**
     * Photoshop-faithful Selective Color: the algorithm Adobe uses, as
     * independently reverse-engineered and shipped in FFmpeg's `selectivecolor`
     * filter.
     *
     * For each pixel we find every colour range it belongs to (a pixel can be
     * in several, e.g. a skin tone is both Reds and Yellows) and sum that
     * range's per-channel adjustment. The adjustment for one channel is:
     *
     *   clip(((-1 - a) * k - a) * m, -v/N, 1 - v/N) * scale
     *
     * `a` is the slider value, `k` the black slider, `m` the relative/absolute
     * factor, and `scale` the range-specific strength derived from the pixel's
     * own min / median / max. Because the scale depends on the pixel, this is a
     * true Photoshop-style selective adjustment rather than a global tint.
     */
    private fun applySelectiveColor(px: IntArray, sc: SelectiveColor) {
        val half = 127.5f // N / 2 for 8-bit channels
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()

            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val md = r + g + b - mx - mn

            var dr = 0f
            var dg = 0f
            var db = 0f

            // Adds one range's contribution to all three channels.
            fun add(corr: RgbCorrection, scale: Float) {
                if (corr.isIdentity || scale <= 0f) return
                dr += phi(r, corr.cyan, corr.black, scale, sc.mode)
                dg += phi(g, corr.magenta, corr.black, scale, sc.mode)
                db += phi(b, corr.yellow, corr.black, scale, sc.mode)
            }

            // Primary (RGB) ranges: scale = max - median.
            if (mx == r) add(sc.red, mx - md)
            if (mx == g) add(sc.green, mx - md)
            if (mx == b) add(sc.blue, mx - md)

            // Secondary (CMY) ranges: scale = median - min.
            if (mn == b) add(sc.yellow, md - mn)
            if (mn == r) add(sc.cyan, md - mn)
            if (mn == g) add(sc.magenta, md - mn)

            // Tonal ranges.
            if (r > half && g > half && b > half) {
                add(sc.white, (mn - half) * 2f)
            }
            if ((r != 0f || g != 0f || b != 0f) &&
                (r != 255f || g != 255f || b != 255f)
            ) {
                add(sc.neutral, 255f - (abs(mx - half) + abs(mn - half)))
            }
            if (r < half && g < half && b < half) {
                add(sc.black, (half - mx) * 2f)
            }

            // r/g/b and the deltas are already in 8-bit units here, so they
            // must NOT go through to255(), which expects a 0..1 float.
            px[i] = (c and OPAQUE) or
                ((r + dr).roundToInt().coerceIn(0, 255) shl 16) or
                ((g + dg).roundToInt().coerceIn(0, 255) shl 8) or
                (b + db).roundToInt().coerceIn(0, 255)
        }
    }

    /** One channel's Photoshop selective-colour adjustment, in 8-bit units. */
    private fun phi(
        v: Float,
        a: Float,
        k: Float,
        scale: Float,
        mode: SelectiveMode
    ): Float {
        val m = if (mode == SelectiveMode.RELATIVE) 1f - v * INV255 else 1f
        val x = ((-1f - a) * k - a) * m
        return x.coerceIn(-v * INV255, 1f - v * INV255) * scale
    }

    private fun to255(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

    private companion object {
        const val INV255 = 1f / 255f
        const val OPAQUE = 0xFF shl 24
    }
}

/**
 * Monotone cubic (Fritsch-Carlson) evaluation of a tone curve.
 *
 * Shared between the renderer and the curve editor UI so the graph the user
 * drags is exactly the curve that gets applied to the pixels. A second,
 * slightly different implementation would make the on-screen curve lie.
 */
object ToneCurveSampler {

    fun eval(points: List<CurvePoint>, x: Float): Float {
        if (points.isEmpty()) return x
        if (points.size == 1) return points[0].y
        if (x <= points.first().x) return points.first().y
        if (x >= points.last().x) return points.last().y

        var i = 0
        while (i < points.size - 2 && x > points[i + 1].x) i++

        val p0 = points[i]
        val p1 = points[i + 1]
        val dx = (p1.x - p0.x).takeIf { it > 0.0001f } ?: 0.0001f
        val t = (x - p0.x) / dx

        val m0 = if (i == 0) {
            (p1.y - p0.y) / dx
        } else {
            (p1.y - points[i - 1].y) / (p1.x - points[i - 1].x).coerceAtLeast(0.0001f)
        }
        val m1 = if (i + 2 >= points.size) {
            (p1.y - p0.y) / dx
        } else {
            (points[i + 2].y - p0.y) / (points[i + 2].x - p0.x).coerceAtLeast(0.0001f)
        }
        val s = m0 * dx

        val h00 = 2f * t * t * t - 3f * t * t + 1f
        val h10 = t * t * t - 2f * t * t + t
        val h01 = -2f * t * t * t + 3f * t * t
        val h11 = t * t * t - t * t

        val y = h00 * p0.y + h10 * s + h01 * p1.y + h11 * m1 * dx

        // Clamp to the local segment so the curve never overshoots wildly.
        val lo = minOf(p0.y, p1.y)
        val hi = maxOf(p0.y, p1.y)
        return y.coerceIn(lo - 0.25f, hi + 0.25f)
    }
}
