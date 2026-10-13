// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.diag

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.ImageReader
import android.os.Build
import android.util.Log
import com.procam.camera.Camera2Controller
import com.procam.camera.CameraCapabilities
import com.procam.camera.ControlMode
import com.procam.camera.ManualControlState
import com.procam.camera.formatShutter
import com.procam.capture.MediaWriter
import kotlinx.coroutines.delay
import kotlin.math.abs

const val DIAG_TAG = "ProCamDiag"

/**
 * Headless on-device verification of the two things the static metadata dump
 * cannot answer:
 *
 *  1. which stream combinations this HAL actually accepts, and
 *  2. whether a requested multi-second exposure is honoured or silently clamped.
 *
 * The preview stream is stood up as a PRIVATE ImageReader rather than a real
 * SurfaceTexture, which is the same stream class from the HAL's point of view
 * but needs no GL context — so this can run before, or instead of, the UI.
 */
class Diagnostics(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    suspend fun runAll(emit: (String) -> Unit) {
        val log: (String) -> Unit = { line ->
            Log.i(DIAG_TAG, line)
            emit(line)
        }

        log("=".repeat(64))
        log("ProCam diagnostics")
        log("device      : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        log("android     : ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        log("build       : ${Build.DISPLAY}")
        log("=".repeat(64))

        // ---------- 1. enumerate ----------
        val cameras = runCatching { CameraCapabilities.openableCameras(manager) }
            .getOrElse {
                log("FATAL: cannot enumerate cameras: ${it.message}")
                return
            }
        log("")
        log("[1] CAMERAS VISIBLE TO THIS APP: ${cameras.size}")
        cameras.forEach { c ->
            log("")
            log(c.summary())
        }

        val target = cameras.firstOrNull { it.facingBack && it.supportsRaw }
            ?: cameras.firstOrNull { it.facingBack }
            ?: cameras.firstOrNull()
        if (target == null) {
            log("FATAL: no camera to test")
            return
        }

        log("")
        log("=".repeat(64))
        log("[2] STREAM COMBINATION — camera \"${target.cameraId}\"")

        val controller = Camera2Controller(context)
        // The drain listener needs a real Looper: diagnostics run on a plain
        // coroutine dispatcher, where ImageReader refuses a null handler.
        val drainThread = android.os.HandlerThread("ProCam-DiagDrain").apply { start() }
        val drainHandler = android.os.Handler(drainThread.looper)
        val previewReader = ImageReader.newInstance(
            target.previewSize.width, target.previewSize.height, ImageFormat.PRIVATE, 3,
        ).apply {
            setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, drainHandler)
        }

        try {
            controller.open(target.cameraId)
            val plan = controller.startSession(previewReader.surface)
            log("accepted    : ${plan.label}")
            log("  histogram : ${if (plan.hasHistogram) "YES (YUV stream granted)" else "NO"}")
            log("  raw       : ${if (plan.raw) "YES" else "NO"}")
            if (!plan.hasHistogram) {
                log("  NOTE: 4-stream preview+YUV+JPEG+RAW was rejected; fell back.")
            }

            delay(600)

            // ---------- 3. exposure honesty ----------
            log("")
            log("=".repeat(64))
            log("[3] LONG EXPOSURE — is SENSOR_EXPOSURE_TIME honoured?")
            log("advertised max = ${target.exposureTimeRange.upper} ns (${target.maxExposureLabel})")
            log("")
            log(
                "%-10s %-14s %-14s %-9s %-8s".format(
                    "REQUEST", "REPORTED", "DELTA", "WALL", "RESULT",
                ),
            )

            val ladder = listOf(
                1_000_000_000L,
                4_000_000_000L,
                8_000_000_000L,
                16_000_000_000L,
                32_000_000_000L,
            ).filter { it <= target.exposureTimeRange.upper }

            var firstClamp: Long? = null

            for (requested in ladder) {
                controller.updateControls(
                    ManualControlState(
                        exposureMode = ControlMode.MANUAL,
                        iso = target.isoRange.lower,
                        exposureTimeNs = requested,
                        focusMode = ControlMode.MANUAL,
                        focusDiopter = 0f,
                        wbMode = ControlMode.MANUAL,
                    ),
                )
                delay(400)

                val shot = controller.captureStill(jpegOrientation = 0, wantRaw = false)
                val reported = shot.result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                shot.rawImage?.close()

                val verdict = when {
                    shot.error != null -> "ERR ${shot.error}"
                    reported == null -> "no metadata"
                    abs(reported - requested) < requested / 20 -> "OK"
                    else -> "CLAMPED"
                }
                if (verdict == "CLAMPED" && firstClamp == null) firstClamp = requested

                log(
                    "%-10s %-14s %-14s %-9s %-8s".format(
                        formatShutter(requested),
                        reported?.let { formatShutter(it) } ?: "-",
                        reported?.let { "${(it - requested) / 1_000_000} ms" } ?: "-",
                        "${shot.wallClockMs} ms",
                        verdict,
                    ),
                )
            }

            log("")
            if (firstClamp == null) {
                log("VERDICT: full ladder honoured — no frame stacking needed.")
            } else {
                log("VERDICT: clamped from ${formatShutter(firstClamp)} up — stacking required above that.")
            }

            // ---------- 4. RAW write ----------
            if (plan.raw) {
                log("")
                log("=".repeat(64))
                log("[4] RAW / DNG WRITE")

                controller.updateControls(
                    ManualControlState(
                        exposureMode = ControlMode.MANUAL,
                        iso = 400,
                        exposureTimeNs = 16_666_666L,
                        focusMode = ControlMode.AUTO,
                    ),
                )
                delay(700)

                val shot = controller.captureStill(jpegOrientation = 90, wantRaw = true)
                if (shot.error != null) {
                    log("capture failed: ${shot.error}")
                } else {
                    val raw = shot.rawImage
                    val result = shot.result
                    log("jpeg bytes  : ${shot.jpegBytes?.size ?: 0}")
                    log("raw image   : ${raw?.width}x${raw?.height} fmt=${raw?.format}")
                    if (raw != null && result != null) {
                        val writer = MediaWriter(context)
                        val (uri, size) = writer.writeDng(
                            raw,
                            result,
                            manager.getCameraCharacteristics(target.cameraId),
                            90,
                            null,
                            "PROCAM_DIAG_${System.currentTimeMillis()}",
                        )
                        log("dng uri     : $uri")
                        log("dng bytes   : $size (${size / 1024 / 1024} MB)")
                        log(if (uri != null && size > 0) "DNG WRITE OK" else "DNG WRITE FAILED")
                    }
                    raw?.close()

                    shot.jpegBytes?.let { bytes ->
                        val writer = MediaWriter(context)
                        val (uri, size) = writer.writeJpeg(
                            bytes, result, target.focalLengthMm, target.aperture, null,
                            "PROCAM_DIAG_${System.currentTimeMillis()}",
                        )
                        log("jpeg uri    : $uri ($size bytes)")
                    }
                }
            } else {
                log("")
                log("[4] RAW test skipped — no RAW stream in the accepted plan")
            }
        } catch (t: Throwable) {
            log("FATAL: ${t::class.java.simpleName}: ${t.message}")
            Log.e(DIAG_TAG, "diagnostics failed", t)
        } finally {
            controller.release()
            runCatching { previewReader.close() }
            drainThread.quitSafely()
        }

        log("")
        log("=".repeat(64))
        log("DIAGNOSTICS COMPLETE")
        log("=".repeat(64))
    }
}
