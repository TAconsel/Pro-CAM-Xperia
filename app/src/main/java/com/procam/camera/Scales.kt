// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.camera

import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import android.util.Size
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Measured values from Nothing Phone (2a) Plus (A142P), camera "0", Android 16 / MT6886.
 *
 * These are FALLBACKS ONLY — [CameraCapabilities] always queries the live
 * CameraCharacteristics. They exist so the UI can build sane scales before the
 * camera opens, and so the diagnostics runner can flag any deviation.
 */
object DeviceProfile {
    const val MAIN_CAMERA_ID = "0"
    const val FRONT_CAMERA_ID = "1"

    val ISO_RANGE: Range<Int> = Range(100, 16000)
    const val MAX_ANALOG_ISO = 1600

    /** 1/10000 s .. 32 s */
    val EXPOSURE_RANGE_NS: Range<Long> = Range(100_000L, 32_000_000_000L)

    /** Diopters (1/m). 20.0 => closest focus ~5 cm. */
    const val MAX_FOCUS_DIOPTER = 20.0f

    val EV_RANGE: Range<Int> = Range(-24, 24)
    const val EV_STEP = 1.0 / 6.0

    const val APERTURE = 1.879f
    const val FOCAL_MM = 5.56f

    val RAW_SIZE = Size(4080, 3072)
    val JPEG_SIZE = Size(4080, 3072)
    val PREVIEW_SIZE = Size(1920, 1440)
    val ANALYSIS_SIZE = Size(640, 480)

    /**
     * Repeating-request exposure ceiling. With AE off the preview frame rate is
     * 1/exposureTime, so a 32 s request would freeze the viewfinder for 32 s.
     * Above this the preview runs at the cap and compensates with ISO.
     */
    const val PREVIEW_EXPOSURE_CAP_NS = 125_000_000L // 1/8 s
}

/** ISO in 1/3 stops across the sensor's full advertised range. */
object IsoScale {
    val values: List<Int> = listOf(
        100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
        1600, // ceiling of analog gain on this sensor
        2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000,
    )

    fun forRange(range: Range<Int>): List<Int> {
        val inRange = values.filter { it in range.lower..range.upper }
        return if (inRange.isEmpty()) listOf(range.lower) else inRange
    }

    fun isDigitalGain(iso: Int, maxAnalog: Int) = iso > maxAnalog

    fun nearestIndex(list: List<Int>, iso: Int): Int {
        if (list.isEmpty()) return 0
        var best = 0
        var bestDiff = Int.MAX_VALUE
        list.forEachIndexed { i, v ->
            val d = abs(v - iso)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return best
    }
}

/** Shutter presets in 1/3 stops from 1/10000 s to 32 s. */
object ShutterScale {
    val presetsNs: List<Long> = listOf(
        100_000L, 125_000L, 160_000L, 200_000L, 250_000L,
        320_000L, 400_000L, 500_000L,
        640_000L, 800_000L, 1_000_000L,
        1_250_000L, 1_600_000L, 2_000_000L,
        2_500_000L, 3_200_000L, 4_000_000L,
        5_000_000L, 6_400_000L, 8_000_000L,
        10_000_000L, 12_500_000L, 16_666_666L,
        20_000_000L, 25_000_000L, 33_333_333L,
        40_000_000L, 50_000_000L, 66_666_666L,
        83_333_333L, 100_000_000L, 125_000_000L,
        166_666_666L, 200_000_000L, 250_000_000L,
        333_333_333L, 400_000_000L, 500_000_000L,
        625_000_000L, 800_000_000L,
        1_000_000_000L, 1_300_000_000L, 1_600_000_000L, 2_000_000_000L,
        2_500_000_000L, 3_200_000_000L, 4_000_000_000L,
        5_000_000_000L, 6_000_000_000L, 8_000_000_000L,
        10_000_000_000L, 13_000_000_000L, 15_000_000_000L,
        20_000_000_000L, 25_000_000_000L, 30_000_000_000L, 32_000_000_000L,
    )

    fun forRange(range: Range<Long>): List<Long> {
        val inRange = presetsNs.filter { it in range.lower..range.upper }
        return if (inRange.isEmpty()) listOf(range.lower) else inRange
    }

    fun nearestIndex(list: List<Long>, ns: Long): Int {
        if (list.isEmpty()) return 0
        var best = 0
        var bestDiff = Long.MAX_VALUE
        list.forEachIndexed { i, v ->
            val d = abs(v - ns)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return best
    }
}

// Readouts are numeric, not prose: pin them to Locale.ROOT so a device locale
// with its own digit shapes cannot turn "1/250" into something unreadable.
private val NUM = java.util.Locale.ROOT

/** "1/250" or "8\"" — DSLR-style shutter notation. */
fun formatShutter(ns: Long): String = when {
    ns >= 1_000_000_000L -> {
        val s = ns / 1_000_000_000.0
        if (abs(s - s.roundToInt()) < 0.05) "${s.roundToInt()}\""
        else String.format(NUM, "%.1f\"", s)
    }
    else -> "1/${(1_000_000_000.0 / ns).roundToInt()}"
}

/** EV compensation steps -> "+1.3" */
fun formatEv(steps: Int, step: Double): String {
    val ev = steps * step
    return when {
        abs(ev) < 0.01 -> "0.0"
        ev > 0 -> String.format(NUM, "+%.1f", ev)
        else -> String.format(NUM, "%.1f", ev)
    }
}

/**
 * Focus slider mapping. Linear-in-diopter spends almost all travel on the close
 * range (0..1 diopter covers 1 m..infinity, i.e. 5% of the scale), so the
 * slider position is squared before scaling.
 *
 * t = 0.00 -> infinity, t = 0.22 -> ~1 m, t = 0.50 -> ~20 cm, t = 1.00 -> ~5 cm
 */
fun sliderToDiopter(t: Float, maxDiopter: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return maxDiopter * c * c
}

fun diopterToSlider(d: Float, maxDiopter: Float): Float {
    if (maxDiopter <= 0f) return 0f
    return (d / maxDiopter).coerceIn(0f, 1f).pow(0.5f)
}

/**
 * Focus distance readout. The sensor reports UNCALIBRATED focus distance, so
 * the numbers are approximate by definition — hence the leading "~".
 */
fun diopterToLabel(d: Float): String = when {
    d < 0.05f -> "∞"
    d < 1f -> String.format(NUM, "~%.1fm", 1f / d)
    d < 10f -> String.format(NUM, "~%.0fcm", 100f / d)
    else -> String.format(NUM, "~%.1fcm", 100f / d)
}

/** Tanner Helland blackbody approximation, 0..255 per channel. */
private fun blackbodyRgb(kelvin: Int): Triple<Double, Double, Double> {
    val t = kelvin.coerceIn(1500, 15000) / 100.0

    val r = if (t <= 66) 255.0
    else (329.698727446 * (t - 60).pow(-0.1332047592)).coerceIn(1.0, 255.0)

    val g = if (t <= 66) (99.4708025861 * ln(t) - 161.1195681661).coerceIn(1.0, 255.0)
    else (288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(1.0, 255.0)

    val b = when {
        t >= 66 -> 255.0
        t <= 19 -> 1.0
        else -> (138.5177312231 * ln(t - 10) - 305.0447927307).coerceIn(1.0, 255.0)
    }
    return Triple(r, g, b)
}

/**
 * Kelvin + tint -> COLOR_CORRECTION_GAINS.
 *
 * These gains do not correct an illuminant in isolation — they map the sensor's
 * *raw* response to neutral, and a Bayer sensor is far from neutral to start
 * with (twice as many green photosites, and green is the most sensitive). Feed
 * it a plain inverse-blackbody ratio near 1.0 and the frame comes out solidly
 * green, because that is what the sensor natively sees.
 *
 * So the maths is anchored instead: [anchorR]/[anchorG]/[anchorB] are the gains
 * AWB was actually using at [anchorKelvin], captured at the moment the user took
 * manual control. Landing back on the anchor temperature reproduces the AWB
 * look exactly, and moving the dial warms or cools relative to it — which is
 * also how the white-balance dial on a DSLR behaves.
 */
fun kelvinToRggb(
    kelvin: Int,
    tint: Float,
    anchorKelvin: Int,
    anchorR: Float,
    anchorG: Float,
    anchorB: Float,
): RggbChannelVector {
    val (ar, ag, ab) = blackbodyRgb(anchorKelvin)
    val (tr, tg, tb) = blackbodyRgb(kelvin)

    // A cooler target illuminant carries less red, so red has to be gained up.
    var r = anchorR * (ar / tr).toFloat()
    var g = anchorG * (ag / tg).toFloat()
    var b = anchorB * (ab / tb).toFloat()

    // + tint = magenta, which means holding green back.
    g *= (1f - tint.coerceIn(-1f, 1f) * 0.3f)

    // Camera2 expects gains at or above 1.0, so scale the weakest channel to 1.
    val floor = minOf(r, g, b).coerceAtLeast(0.0001f)
    r /= floor; g /= floor; b /= floor

    return RggbChannelVector(
        r.coerceIn(1f, 8f),
        g.coerceIn(1f, 8f),
        g.coerceIn(1f, 8f),
        b.coerceIn(1f, 8f),
    )
}

/** Fallback anchor when the HAL does not report the AWB gains it used. */
val DEFAULT_WB_ANCHOR = Triple(1.95f, 1.0f, 1.75f)

val KELVIN_PRESETS = listOf(
    2000, 2200, 2500, 2800, 3000, 3200, 3500, 4000, 4500, 5000,
    5500, 6000, 6500, 7000, 7500, 8000, 8500, 9000, 9500, 10000,
)
