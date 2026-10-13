// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.analysis

import android.media.Image
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 256-bin histogram of one preview frame.
 *
 * [luma] is BT.709 luma straight from the Y plane. [r]/[g]/[b] come from a
 * YUV->RGB conversion of the same subsampled grid, so all four are directly
 * comparable.
 */
class HistogramData(
    val luma: IntArray,
    val r: IntArray,
    val g: IntArray,
    val b: IntArray,
    val samples: Int,
    /** Fraction of samples at or above the clipping threshold. */
    val clippedFraction: Float,
    /** Fraction of samples at or below the crush threshold. */
    val crushedFraction: Float,
) {
    val peak: Int = maxOf(
        luma.maxOrNull() ?: 1,
        r.maxOrNull() ?: 1,
        g.maxOrNull() ?: 1,
        b.maxOrNull() ?: 1,
    ).coerceAtLeast(1)

    companion object {
        val EMPTY = HistogramData(
            IntArray(256), IntArray(256), IntArray(256), IntArray(256), 0, 0f, 0f,
        )
    }
}

/**
 * CPU histogram over the YUV_420_888 analysis stream.
 *
 * Runs on the analysis HandlerThread, subsamples on a fixed grid (~19k samples
 * from a 640x480 frame) and throttles to [minIntervalMs]. That keeps a full
 * RGB+luma pass under a couple of milliseconds, which is cheap enough to leave
 * the GPU entirely free for the preview shaders.
 */
class HistogramEngine(
    private val minIntervalMs: Long = 66L, // ~15 Hz
    private val step: Int = 4,
) {
    private val _data = MutableStateFlow(HistogramData.EMPTY)
    val data: StateFlow<HistogramData> = _data

    private var lastRunAt = 0L

    @Volatile
    var enabled: Boolean = true

    private val lumaBins = IntArray(256)
    private val rBins = IntArray(256)
    private val gBins = IntArray(256)
    private val bBins = IntArray(256)

    /** Call from the ImageReader listener. The image must stay valid for the call. */
    fun submit(image: Image) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRunAt < minIntervalMs) return
        lastRunAt = now

        if (image.format != android.graphics.ImageFormat.YUV_420_888) return
        val planes = image.planes
        if (planes.size < 3) return

        val width = image.width
        val height = image.height

        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride

        java.util.Arrays.fill(lumaBins, 0)
        java.util.Arrays.fill(rBins, 0)
        java.util.Arrays.fill(gBins, 0)
        java.util.Arrays.fill(bBins, 0)

        var samples = 0
        var clipped = 0
        var crushed = 0

        var y = 0
        while (y < height) {
            val yBase = y * yRow
            val cBase = (y / 2)
            var x = 0
            while (x < width) {
                val yIdx = yBase + x * yPix
                if (yIdx >= yBuf.limit()) { x += step; continue }
                val yv = yBuf.get(yIdx).toInt() and 0xFF

                val uIdx = cBase * uRow + (x / 2) * uPix
                val vIdx = cBase * vRow + (x / 2) * vPix
                val uv = if (uIdx < uBuf.limit()) (uBuf.get(uIdx).toInt() and 0xFF) - 128 else 0
                val vv = if (vIdx < vBuf.limit()) (vBuf.get(vIdx).toInt() and 0xFF) - 128 else 0

                // BT.601 full-range inverse — matches what YUV_420_888 carries.
                val rr = clamp8(yv + ((91881 * vv) shr 16))
                val gg = clamp8(yv - ((22554 * uv + 46802 * vv) shr 16))
                val bb = clamp8(yv + ((116130 * uv) shr 16))

                lumaBins[yv]++
                rBins[rr]++
                gBins[gg]++
                bBins[bb]++

                if (yv >= 250) clipped++
                if (yv <= 4) crushed++
                samples++

                x += step
            }
            y += step
        }

        if (samples == 0) return

        _data.value = HistogramData(
            luma = lumaBins.copyOf(),
            r = rBins.copyOf(),
            g = gBins.copyOf(),
            b = bBins.copyOf(),
            samples = samples,
            clippedFraction = clipped.toFloat() / samples,
            crushedFraction = crushed.toFloat() / samples,
        )
    }

    fun reset() {
        _data.value = HistogramData.EMPTY
    }

    private fun clamp8(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
}
