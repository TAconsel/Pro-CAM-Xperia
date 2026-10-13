// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform

enum class ControlMode { AUTO, MANUAL }

enum class FlashMode { OFF, TORCH, SINGLE }

/**
 * The full manual state of the camera. One immutable snapshot drives both the
 * repeating preview request and the still-capture request, so what you meter
 * is what you shoot.
 */
data class ManualControlState(
    val exposureMode: ControlMode = ControlMode.AUTO,
    val iso: Int = 400,
    val exposureTimeNs: Long = 16_666_666L, // 1/60 s

    val focusMode: ControlMode = ControlMode.AUTO,
    val focusDiopter: Float = 0f, // 0 = infinity

    val wbMode: ControlMode = ControlMode.AUTO,
    val wbKelvin: Int = 5500,
    val wbTint: Float = 0f, // -1 green .. +1 magenta

    /** AWB's own gains, sampled when manual WB was engaged. See [kelvinToRggb]. */
    val wbAnchorKelvin: Int = 5500,
    val wbAnchorR: Float = 1.95f,
    val wbAnchorG: Float = 1.0f,
    val wbAnchorB: Float = 1.75f,

    val evSteps: Int = 0, // only meaningful while AE is on

    val flashMode: FlashMode = FlashMode.OFF,
    val noiseReduction: Int = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,

    // Monitoring overlays
    val histogramEnabled: Boolean = true,
    val peakingEnabled: Boolean = false,
    // 0.16 lights up almost every edge in a high-contrast scene; 0.26 keeps the
    // highlight on what is genuinely sharp. Tune live with PEAK LV.
    val peakingThreshold: Float = 0.26f,
    val peakingColor: PeakColor = PeakColor.GREEN,
    val zebraEnabled: Boolean = false,
    val zebraThreshold: Float = 0.94f,
    val gridEnabled: Boolean = false,
    val levelEnabled: Boolean = false,

    val rawEnabled: Boolean = true,

    /**
     * Off by default on purpose: once a GPS tag is in the file it travels with
     * every copy you share, and a camera app should not make that decision for
     * you.
     */
    val gpsEnabled: Boolean = false,
) {
    val shutterLabel: String get() = formatShutter(exposureTimeNs)
    val focusLabel: String get() = diopterToLabel(focusDiopter)
}

enum class PeakColor(val rgb: Triple<Float, Float, Float>, val label: String) {
    GREEN(Triple(0.10f, 1.00f, 0.30f), "GRN"),
    RED(Triple(1.00f, 0.10f, 0.15f), "RED"),
    WHITE(Triple(1.00f, 1.00f, 1.00f), "WHT"),
}

private val IDENTITY_TRANSFORM = ColorSpaceTransform(
    intArrayOf(
        1, 1, 0, 1, 0, 1,
        0, 1, 1, 1, 0, 1,
        0, 1, 0, 1, 1, 1,
    ),
)

/**
 * Applies the state to a request builder.
 *
 * @param previewCap when non-null, exposures longer than this are clamped and
 *   the shortfall is made up with ISO. Used only for the repeating request —
 *   at AE_MODE_OFF the preview frame rate is 1/exposureTime, so an unclamped
 *   32 s request would stall the viewfinder for 32 s per frame.
 */
fun CaptureRequest.Builder.applyControls(
    s: ManualControlState,
    caps: CameraCapabilities,
    previewCap: Long? = null,
) {
    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

    // ---------------- exposure ----------------
    if (s.exposureMode == ControlMode.MANUAL && caps.supportsManualSensor) {
        var iso = s.iso.coerceIn(caps.isoRange.lower, caps.isoRange.upper)
        var exposure = s.exposureTimeNs
            .coerceIn(caps.exposureTimeRange.lower, caps.exposureTimeRange.upper)

        if (previewCap != null && exposure > previewCap) {
            val ratio = exposure.toDouble() / previewCap
            iso = (iso * ratio).toInt().coerceIn(caps.isoRange.lower, caps.isoRange.upper)
            exposure = previewCap
        }

        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        // Frame duration must be >= exposure or the HAL truncates long exposures.
        set(
            CaptureRequest.SENSOR_FRAME_DURATION,
            exposure.coerceAtMost(caps.maxFrameDurationNs),
        )
    } else {
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AE_LOCK, false)
        set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            s.evSteps.coerceIn(caps.evRange.lower, caps.evRange.upper),
        )
    }

    // ---------------- focus ----------------
    if (s.focusMode == ControlMode.MANUAL && caps.supportsManualFocus) {
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        set(
            CaptureRequest.LENS_FOCUS_DISTANCE,
            s.focusDiopter.coerceIn(0f, caps.minFocusDistance),
        )
    } else {
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    // ---------------- white balance ----------------
    if (s.wbMode == ControlMode.MANUAL && caps.supportsManualPostProcessing) {
        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        set(
            CaptureRequest.COLOR_CORRECTION_MODE,
            CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
        )
        set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM)
        set(
            CaptureRequest.COLOR_CORRECTION_GAINS,
            kelvinToRggb(
                kelvin = s.wbKelvin,
                tint = s.wbTint,
                anchorKelvin = s.wbAnchorKelvin,
                anchorR = s.wbAnchorR,
                anchorG = s.wbAnchorG,
                anchorB = s.wbAnchorB,
            ),
        )
    } else {
        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    // ---------------- flash ----------------
    if (caps.hasFlash) {
        when (s.flashMode) {
            FlashMode.OFF -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            FlashMode.TORCH -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            FlashMode.SINGLE -> set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
    }

    set(CaptureRequest.NOISE_REDUCTION_MODE, s.noiseReduction)
}

/** Flash fires only on the still request, never on the repeating preview. */
fun CaptureRequest.Builder.applyStillFlash(s: ManualControlState, caps: CameraCapabilities) {
    if (caps.hasFlash && s.flashMode == FlashMode.SINGLE) {
        set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
    }
}
