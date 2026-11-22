package com.alex.photoeditor.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks the Selective Color engine against the reference values published in
 * "Understanding selective coloring in Adobe Photoshop" (the reverse
 * engineering behind FFmpeg's `selectivecolor` filter).
 */
class SelectiveColorTest {

    private fun apply(
        r: Int, g: Int, b: Int,
        reds: RgbCorrection = RgbCorrection(),
        yellows: RgbCorrection = RgbCorrection(),
        whites: RgbCorrection = RgbCorrection(),
        mode: SelectiveMode = SelectiveMode.ABSOLUTE
    ): IntArray {
        val px = intArrayOf((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        val sc = SelectiveColor(
            red = reds,
            yellow = yellows,
            white = whites,
            mode = mode,
            isEnabled = true
        )
        ColorGradeEngine().applyInPlace(px, ColorGrade(selectiveColor = sc))
        return px
    }

    @Test
    fun `reds cyan plus 40 percent matches Photoshop`() {
        // Omega = max - median = 180 - 100 = 80; dR = -0.4 * 80 = -32.
        val out = apply(180, 100, 50, reds = RgbCorrection(cyan = 0.4f))
        assertEquals(148, channel(out, 16))
    }

    @Test
    fun `reds cyan minus 60 percent matches Photoshop`() {
        // The +0.6 slope would give +48, but the upper clip is
        // (N - v) * Omega / N = 75 * 80 / 255 = +23.5 -> 204.
        val out = apply(180, 100, 50, reds = RgbCorrection(cyan = -0.6f))
        assertEquals(204, channel(out, 16))
    }

    @Test
    fun `reds magenta minus 60 percent drives the green channel`() {
        val out = apply(180, 100, 50, reds = RgbCorrection(magenta = -0.6f))
        assertEquals(148, channel(out, 8))
    }

    @Test
    fun `reds yellow plus 10 percent drives the blue channel`() {
        val out = apply(180, 100, 50, reds = RgbCorrection(yellow = 0.1f))
        assertEquals(42, channel(out, 0))
    }

    @Test
    fun `reds adjustment does not touch a blue pixel`() {
        val out = apply(50, 100, 180, reds = RgbCorrection(cyan = 0.4f))
        assertEquals(50, channel(out, 16))
        assertEquals(100, channel(out, 8))
        assertEquals(180, channel(out, 0))
    }

    @Test
    fun `yellows use the median minus min scale`() {
        // (180,100,50) is also a Yellow: Omega = median - min = 100 - 50 = 50.
        // Yellow +100% clips at -b/N = -50/255, so dB = -50/255 * 50 = -9.8.
        val out = apply(180, 100, 50, yellows = RgbCorrection(yellow = 1f))
        assertEquals(40, channel(out, 0))
    }

    private fun channel(px: IntArray, shift: Int): Int = (px[0] shr shift) and 0xFF
}
