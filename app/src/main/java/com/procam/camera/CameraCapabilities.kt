// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size

/**
 * Everything the UI and request builder need, resolved once per camera.
 *
 * Nothing is hard-coded from the spec sheet: every field comes from the live
 * CameraCharacteristics, with [DeviceProfile] only as a last-resort fallback.
 */
data class CameraCapabilities(
    val cameraId: String,
    val facingBack: Boolean,
    val hardwareLevel: Int,
    val capabilities: List<Int>,
    val isoRange: Range<Int>,
    val maxAnalogIso: Int,
    val exposureTimeRange: Range<Long>,
    val maxFrameDurationNs: Long,
    val minFocusDistance: Float,
    val focusCalibration: Int,
    val evRange: Range<Int>,
    val evStep: Double,
    val aperture: Float,
    val focalLengthMm: Float,
    val hasFlash: Boolean,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val sensorOrientation: Int,
    val activeArraySize: Rect,
    val rawSize: Size?,
    val jpegSize: Size?,
    val previewSize: Size,
    val analysisSize: Size,
    val availableAnalysisSizes: List<Size>,
) {
    val supportsManualSensor: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities

    val supportsManualPostProcessing: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities

    val supportsRaw: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities && rawSize != null

    val supportsManualFocus: Boolean
        get() = minFocusDistance > 0f

    val hardwareLevelName: String
        get() = when (hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN($hardwareLevel)"
        }

    val capabilityNames: List<String>
        get() = capabilities.map { CAPABILITY_NAMES[it] ?: "UNKNOWN($it)" }

    val maxExposureSeconds: Double
        get() = exposureTimeRange.upper / 1_000_000_000.0

    val minExposureLabel: String get() = formatShutter(exposureTimeRange.lower)
    val maxExposureLabel: String get() = formatShutter(exposureTimeRange.upper)

    fun summary(): String = buildString {
        appendLine("camera \"$cameraId\" (${if (facingBack) "BACK" else "FRONT"}) $hardwareLevelName")
        appendLine("  capabilities      : ${capabilityNames.joinToString(" ")}")
        appendLine("  iso               : ${isoRange.lower}..${isoRange.upper} (analog<=$maxAnalogIso)")
        appendLine("  exposure          : ${exposureTimeRange.lower}..${exposureTimeRange.upper} ns  ($minExposureLabel .. $maxExposureLabel)")
        appendLine("  maxFrameDuration  : $maxFrameDurationNs ns")
        appendLine("  minFocusDistance  : $minFocusDistance diopters (manualFocus=$supportsManualFocus)")
        appendLine("  focusCalibration  : ${focusCalibrationName(focusCalibration)}")
        appendLine("  ev                : ${evRange.lower}..${evRange.upper} step=$evStep  (+-${"%.1f".format(evRange.upper * evStep)} EV)")
        appendLine("  aperture / focal  : f/$aperture  ${focalLengthMm}mm  flash=$hasFlash")
        appendLine("  metering regions  : af=$maxAfRegions ae=$maxAeRegions")
        appendLine("  sensorOrientation : $sensorOrientation")
        appendLine("  activeArray       : ${activeArraySize.width()}x${activeArraySize.height()}")
        appendLine("  raw  size         : ${rawSize?.let { "${it.width}x${it.height}" } ?: "NOT SUPPORTED"}")
        appendLine("  jpeg size         : ${jpegSize?.let { "${it.width}x${it.height}" } ?: "-"}")
        appendLine("  preview size      : ${previewSize.width}x${previewSize.height}")
        append("  analysis size     : ${analysisSize.width}x${analysisSize.height}")
    }

    companion object {
        private val CAPABILITY_NAMES = mapOf(
            0 to "BACKWARD_COMPATIBLE",
            1 to "MANUAL_SENSOR",
            2 to "MANUAL_POST_PROCESSING",
            3 to "RAW",
            4 to "PRIVATE_REPROCESSING",
            5 to "READ_SENSOR_SETTINGS",
            6 to "BURST_CAPTURE",
            7 to "YUV_REPROCESSING",
            8 to "DEPTH_OUTPUT",
            9 to "CONSTRAINED_HIGH_SPEED_VIDEO",
            10 to "MOTION_TRACKING",
            11 to "LOGICAL_MULTI_CAMERA",
            12 to "MONOCHROME",
            13 to "SECURE_IMAGE_DATA",
            14 to "SYSTEM_CAMERA",
            15 to "OFFLINE_PROCESSING",
            16 to "ULTRA_HIGH_RESOLUTION_SENSOR",
            17 to "REMOSAIC_REPROCESSING",
            18 to "DYNAMIC_RANGE_TEN_BIT",
            19 to "STREAM_USE_CASE",
            20 to "COLOR_SPACE_PROFILES",
        )

        fun focusCalibrationName(v: Int): String = when (v) {
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
            else -> "UNKNOWN($v)"
        }

        /** Largest 4:3 size at or below 1920 wide — matches the sensor aspect. */
        private fun pickPreviewSize(candidates: Array<Size>?): Size {
            val fallback = DeviceProfile.PREVIEW_SIZE
            if (candidates == null || candidates.isEmpty()) return fallback
            return candidates
                .filter { it.width <= 1920 && it.height <= 1920 }
                .filter { kotlin.math.abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: candidates.minByOrNull {
                    kotlin.math.abs(it.width.toLong() * it.height - 1920L * 1440L)
                }
                ?: fallback
        }

        private fun pickAnalysisSize(candidates: Array<Size>?): Size {
            val fallback = DeviceProfile.ANALYSIS_SIZE
            if (candidates == null || candidates.isEmpty()) return fallback
            return candidates.firstOrNull { it.width == 640 && it.height == 480 }
                ?: candidates.filter { it.width <= 800 }
                    .maxByOrNull { it.width.toLong() * it.height }
                ?: fallback
        }

        fun from(id: String, c: CameraCharacteristics): CameraCapabilities {
            val caps = (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0))
                .toList()
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            val privSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)

            return CameraCapabilities(
                cameraId = id,
                facingBack = c.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK,
                hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                capabilities = caps,
                isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    ?: DeviceProfile.ISO_RANGE,
                maxAnalogIso = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
                    ?: DeviceProfile.MAX_ANALOG_ISO,
                exposureTimeRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    ?: DeviceProfile.EXPOSURE_RANGE_NS,
                maxFrameDurationNs = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                    ?: DeviceProfile.EXPOSURE_RANGE_NS.upper,
                minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    ?: 0f,
                focusCalibration = c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
                    ?: CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED,
                evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    ?: Range(0, 0),
                evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble()
                    ?: 1.0,
                aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.firstOrNull() ?: 0f,
                focalLengthMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: 0f,
                hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
                maxAfRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                maxAeRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                activeArraySize = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?: Rect(),
                rawSize = rawSizes?.maxByOrNull { it.width.toLong() * it.height },
                jpegSize = jpegSizes?.maxByOrNull { it.width.toLong() * it.height },
                previewSize = pickPreviewSize(privSizes ?: yuvSizes),
                analysisSize = pickAnalysisSize(yuvSizes),
                availableAnalysisSizes = yuvSizes?.filter { it.width <= 800 }?.toList() ?: emptyList(),
            )
        }

        /**
         * Cameras a normal app may actually open. On this device three of the
         * five are flagged SYSTEM_CAMERA and are reserved for the OEM app.
         */
        fun openableCameras(manager: CameraManager): List<CameraCapabilities> =
            manager.cameraIdList.mapNotNull { id ->
                runCatching {
                    from(id, manager.getCameraCharacteristics(id))
                }.getOrNull()
            }
    }
}
