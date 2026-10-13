// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.vm

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureResult
import android.location.Location
import android.location.LocationManager
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.procam.analysis.HistogramEngine
import com.procam.camera.Camera2Controller
import com.procam.camera.CameraCapabilities
import com.procam.camera.ControlMode
import com.procam.camera.DEFAULT_WB_ANCHOR
import com.procam.camera.DeviceProfile
import com.procam.camera.FlashMode
import com.procam.camera.IsoScale
import com.procam.camera.KELVIN_PRESETS
import com.procam.camera.ManualControlState
import com.procam.camera.PeakColor
import com.procam.camera.ShutterScale
import com.procam.camera.StreamPlan
import com.procam.camera.diopterToSlider
import com.procam.camera.formatShutter
import com.procam.camera.sliderToDiopter
import com.procam.capture.MediaWriter
import com.procam.gl.OverlaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ProCam.VM"

enum class ShootMode { P, S, M, MR }

enum class ScrubTarget { EV, ISO, SHUTTER, WB, TINT, FOCUS, PEAK, ZEBRA }

/** Where the user last tapped to focus, in preview-view fractions. */
data class FocusPoint(val x: Float, val y: Float, val stamp: Long)

data class UiState(
    val opening: Boolean = true,
    val ready: Boolean = false,
    val fatal: String? = null,
    val notice: String? = null,

    val caps: CameraCapabilities? = null,
    val plan: StreamPlan? = null,
    val cameras: List<CameraCapabilities> = emptyList(),

    val mode: ShootMode = ShootMode.P,
    val scrubTarget: ScrubTarget = ScrubTarget.EV,
    val controls: ManualControlState = ManualControlState(),
    val locked: Boolean = false,
    val showRgbHistogram: Boolean = false,
    val mrPresent: Boolean = false,

    val liveIso: Int? = null,
    val liveExposureNs: Long? = null,
    val liveFocus: Float? = null,
    val liveAperture: Float? = null,
    val afState: Int? = null,
    val focusPoint: FocusPoint? = null,

    val busy: Boolean = false,
    val lastSaved: String? = null,
    val previewSimulated: Boolean = false,

    /** Manual override on top of the computed preview rotation, for new devices. */
    val previewRotationOffset: Int = 0,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = Camera2Controller(app)
    private val mediaWriter = MediaWriter(app)
    val histogram = HistogramEngine()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val _overlay = MutableStateFlow(OverlaySettings())
    val overlay: StateFlow<OverlaySettings> = _overlay

    /** Degrees clockwise from natural orientation, from OrientationEventListener. */
    @Volatile
    var deviceOrientation: Int = 0

    private val mrStore = MemoryRecallStore(app)
    private var previewSurface: Surface? = null
    private var lastServoAt = 0L

    init {
        _ui.value = _ui.value.copy(mrPresent = mrStore.hasSlot())

        controller.onAnalysisFrame = { image ->
            histogram.submit(image)
            if (_ui.value.mode == ShootMode.S) runShutterPriorityServo()
        }

        viewModelScope.launch {
            controller.liveResult.collect { r ->
                if (r == null) return@collect
                _ui.value = _ui.value.copy(
                    liveIso = r.get(CaptureResult.SENSOR_SENSITIVITY),
                    liveExposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    liveFocus = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    liveAperture = r.get(CaptureResult.LENS_APERTURE),
                    afState = r.get(CaptureResult.CONTROL_AF_STATE),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    fun openCamera() {
        if (_ui.value.caps != null) return
        viewModelScope.launch {
            try {
                val cameras = controller.availableCameras()
                val id = controller.bestCameraId()
                controller.open(id)
                _ui.value = _ui.value.copy(
                    opening = false,
                    caps = controller.capabilities,
                    cameras = cameras,
                )
                applyDefaultsFor(controller.capabilities)
            } catch (t: Throwable) {
                Log.e(TAG, "openCamera failed", t)
                _ui.value = _ui.value.copy(opening = false, fatal = t.message ?: "open failed")
            }
        }
    }

    fun onSurfaceTextureReady(st: SurfaceTexture) {
        if (previewSurface != null) return
        val surface = Surface(st)
        previewSurface = surface
        viewModelScope.launch {
            try {
                val plan = controller.startSession(surface)
                histogram.enabled = plan.hasHistogram
                _ui.value = _ui.value.copy(
                    ready = true,
                    plan = plan,
                    notice = if (!plan.hasHistogram) {
                        "HAL rejected the YUV analysis stream — histogram off (${plan.label})"
                    } else if (!plan.raw) {
                        "HAL rejected the RAW stream — DNG unavailable (${plan.label})"
                    } else {
                        null
                    },
                )
            } catch (t: Throwable) {
                Log.e(TAG, "startSession failed", t)
                _ui.value = _ui.value.copy(fatal = t.message ?: "session failed")
            }
        }
    }

    fun closeCamera() {
        controller.close()
        previewSurface?.release()
        previewSurface = null
        _ui.value = _ui.value.copy(ready = false, caps = null, plan = null, opening = true)
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // defaults
    // ------------------------------------------------------------------

    private fun applyDefaultsFor(caps: CameraCapabilities) {
        val iso = IsoScale.forRange(caps.isoRange).let { it.getOrElse(it.size / 3) { 400 } }
        push(
            _ui.value.controls.copy(
                iso = iso,
                exposureTimeNs = 16_666_666L.coerceIn(
                    caps.exposureTimeRange.lower, caps.exposureTimeRange.upper,
                ),
                rawEnabled = caps.supportsRaw,
            ),
        )
    }

    // ------------------------------------------------------------------
    // scales for the current camera
    // ------------------------------------------------------------------

    fun isoValues(): List<Int> =
        _ui.value.caps?.let { IsoScale.forRange(it.isoRange) } ?: IsoScale.values

    fun shutterValues(): List<Long> =
        _ui.value.caps?.let { ShutterScale.forRange(it.exposureTimeRange) } ?: ShutterScale.presetsNs

    fun kelvinValues(): List<Int> = KELVIN_PRESETS

    fun focusValues(): List<Float> = (0..20).map { it / 20f }

    // ------------------------------------------------------------------
    // control mutation
    // ------------------------------------------------------------------

    private fun push(next: ManualControlState) {
        _ui.value = _ui.value.copy(
            controls = next,
            previewSimulated = next.exposureMode == ControlMode.MANUAL &&
                next.exposureTimeNs > DeviceProfile.PREVIEW_EXPOSURE_CAP_NS,
        )
        controller.updateControls(next)
        _overlay.value = OverlaySettings(
            peakingEnabled = next.peakingEnabled,
            peakingThreshold = next.peakingThreshold,
            peakingR = next.peakingColor.rgb.first,
            peakingG = next.peakingColor.rgb.second,
            peakingB = next.peakingColor.rgb.third,
            zebraEnabled = next.zebraEnabled,
            zebraThreshold = next.zebraThreshold,
        )
        histogram.enabled = next.histogramEnabled && (_ui.value.plan?.hasHistogram ?: false)
    }

    /**
     * Carries the metered exposure into manual mode so switching out of P does
     * not make the image jump — the same courtesy a DSLR does when you turn the
     * mode dial.
     */
    private fun seedFromLive(c: ManualControlState): ManualControlState {
        val caps = _ui.value.caps ?: return c
        val iso = _ui.value.liveIso ?: c.iso
        val exp = _ui.value.liveExposureNs ?: c.exposureTimeNs
        return c.copy(
            iso = iso.coerceIn(caps.isoRange.lower, caps.isoRange.upper),
            exposureTimeNs = exp.coerceIn(
                caps.exposureTimeRange.lower, caps.exposureTimeRange.upper,
            ),
        )
    }

    fun setMode(mode: ShootMode) {
        if (_ui.value.locked) return
        val caps = _ui.value.caps
        if (caps != null && !caps.supportsManualSensor && mode != ShootMode.P) {
            _ui.value = _ui.value.copy(notice = "camera ${caps.cameraId} has no MANUAL_SENSOR")
            return
        }
        var c = _ui.value.controls
        when (mode) {
            ShootMode.P -> c = c.copy(exposureMode = ControlMode.AUTO)
            ShootMode.S, ShootMode.M ->
                c = seedFromLive(c).copy(exposureMode = ControlMode.MANUAL)

            ShootMode.MR -> {
                val slot = mrStore.load()
                if (slot == null) {
                    _ui.value = _ui.value.copy(
                        notice = "MR slot is empty — long-press MR to store the current setup",
                    )
                    return
                }
                c = slot.second
            }
        }
        _ui.value = _ui.value.copy(
            mode = mode,
            scrubTarget = when (mode) {
                ShootMode.P -> ScrubTarget.EV
                ShootMode.S -> ScrubTarget.SHUTTER
                ShootMode.M -> ScrubTarget.SHUTTER
                ShootMode.MR -> _ui.value.scrubTarget
            },
        )
        push(c)
    }

    fun saveMemoryRecall() {
        mrStore.save(_ui.value.mode, _ui.value.controls)
        _ui.value = _ui.value.copy(
            mrPresent = true,
            notice = "MR stored: ${_ui.value.mode} ISO${_ui.value.controls.iso} " +
                formatShutter(_ui.value.controls.exposureTimeNs),
        )
    }

    fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        getApplication(), Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * @param requestPermission invoked when the user is switching GPS tagging on
     *   but has not granted location yet. Location is requested at this point
     *   rather than at launch, so the permission is only asked for when it is
     *   actually about to be used.
     */
    fun toggleGps(requestPermission: () -> Unit) {
        val c = _ui.value.controls
        if (!c.gpsEnabled && !hasLocationPermission()) {
            requestPermission()
            return
        }
        push(c.copy(gpsEnabled = !c.gpsEnabled))
    }

    fun setScrubTarget(t: ScrubTarget) {
        if (_ui.value.locked) return
        _ui.value = _ui.value.copy(scrubTarget = t)
    }

    // ------------------------------------------------------------------
    // deck buttons: selecting a parameter also puts the camera in a mode where
    // that parameter is actually adjustable. Camera2 cannot pin ISO while AE is
    // running, so "tap ISO in P mode" has to mean "leave P".
    // ------------------------------------------------------------------

    fun selectIso() {
        if (_ui.value.locked) return
        if (_ui.value.mode != ShootMode.M) setMode(ShootMode.M)
        setScrubTarget(ScrubTarget.ISO)
    }

    fun selectShutter() {
        if (_ui.value.locked) return
        if (_ui.value.mode == ShootMode.P || _ui.value.mode == ShootMode.MR) {
            setMode(ShootMode.S)
        }
        setScrubTarget(ScrubTarget.SHUTTER)
    }

    /**
     * Samples the gains AWB is currently using and pins them as the reference
     * for the displayed Kelvin, so handing over to manual WB does not change
     * the picture — it only hands you the dial.
     */
    private fun anchorWhiteBalance(c: ManualControlState): ManualControlState {
        if (c.wbMode == ControlMode.MANUAL) return c
        val gains = controller.liveResult.value?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        return c.copy(
            wbMode = ControlMode.MANUAL,
            wbAnchorKelvin = c.wbKelvin,
            wbAnchorR = gains?.red ?: DEFAULT_WB_ANCHOR.first,
            wbAnchorG = gains?.let { (it.greenEven + it.greenOdd) / 2f }
                ?: DEFAULT_WB_ANCHOR.second,
            wbAnchorB = gains?.blue ?: DEFAULT_WB_ANCHOR.third,
        )
    }

    fun selectWb() {
        if (_ui.value.locked) return
        push(anchorWhiteBalance(_ui.value.controls))
        setScrubTarget(ScrubTarget.WB)
    }

    fun selectFocus() {
        if (_ui.value.locked) return
        val caps = _ui.value.caps
        if (caps == null || !caps.supportsManualFocus) {
            _ui.value = _ui.value.copy(notice = "this camera is fixed focus")
            return
        }
        val c = _ui.value.controls
        if (c.focusMode == ControlMode.MANUAL) {
            // already in MF — a second tap just re-selects the ruler
            setScrubTarget(ScrubTarget.FOCUS)
            return
        }
        // Pick up wherever AF had landed so the image does not snap, and turn
        // peaking on: manual focus on a phone screen is guesswork without it.
        val live = _ui.value.liveFocus ?: c.focusDiopter
        push(
            c.copy(
                focusMode = ControlMode.MANUAL,
                focusDiopter = live.coerceIn(0f, caps.minFocusDistance),
                peakingEnabled = true,
            ),
        )
        // an AF box means nothing once the lens is driven by hand
        controller.clearFocusRegion()
        _ui.value = _ui.value.copy(focusPoint = null)
        setScrubTarget(ScrubTarget.FOCUS)
    }

    fun setIso(v: Int) = push(_ui.value.controls.copy(iso = v))

    fun setShutter(ns: Long) = push(_ui.value.controls.copy(exposureTimeNs = ns))

    fun setEv(steps: Int) = push(_ui.value.controls.copy(evSteps = steps))

    fun setKelvin(k: Int) = push(_ui.value.controls.copy(wbKelvin = k, wbMode = ControlMode.MANUAL))

    fun setTint(t: Float) = push(_ui.value.controls.copy(wbTint = t, wbMode = ControlMode.MANUAL))

    fun selectTint() {
        if (_ui.value.locked) return
        push(anchorWhiteBalance(_ui.value.controls))
        setScrubTarget(ScrubTarget.TINT)
    }

    /** -10..+10 in the UI; negative pulls green, positive pulls magenta. */
    fun tintSteps(): List<Int> = (-10..10).toList()

    // ------------------------------------------------------------------
    // touch AF
    // ------------------------------------------------------------------

    /**
     * @param nx/@param ny tap position as a fraction of the preview view.
     * @param previewRotation degrees the sensor image is rotated by for display.
     */
    fun tapToFocus(nx: Float, ny: Float, previewRotation: Int, mirrored: Boolean) {
        if (_ui.value.locked) return
        val caps = _ui.value.caps ?: return
        if (caps.maxAfRegions <= 0) {
            _ui.value = _ui.value.copy(notice = "camera reports no AF metering regions")
            return
        }
        if (_ui.value.controls.focusMode == ControlMode.MANUAL) {
            _ui.value = _ui.value.copy(notice = "in manual focus — tap AF-ON first")
            return
        }

        val (sx, sy) = displayToSensor(nx, ny, previewRotation, mirrored)
        controller.focusAt(sx, sy)
        _ui.value = _ui.value.copy(
            focusPoint = FocusPoint(nx, ny, android.os.SystemClock.uptimeMillis()),
        )
    }

    fun clearFocusPoint() {
        _ui.value = _ui.value.copy(focusPoint = null)
    }

    /**
     * The preview is the sensor image rotated clockwise by [rotation] (and
     * mirrored for the front camera), so a tap has to travel the same path
     * backwards before it means anything to CONTROL_AF_REGIONS.
     */
    private fun displayToSensor(
        nx: Float,
        ny: Float,
        rotation: Int,
        mirrored: Boolean,
    ): Pair<Float, Float> {
        val dx = if (mirrored) 1f - nx else nx
        val dy = ny
        return when (((rotation % 360) + 360) % 360) {
            90 -> dy to (1f - dx)
            180 -> (1f - dx) to (1f - dy)
            270 -> (1f - dy) to dx
            else -> dx to dy
        }
    }

    fun setFocusSlider(t: Float) {
        val maxD = _ui.value.caps?.minFocusDistance ?: DeviceProfile.MAX_FOCUS_DIOPTER
        push(
            _ui.value.controls.copy(
                focusDiopter = sliderToDiopter(t, maxD),
                focusMode = ControlMode.MANUAL,
            ),
        )
    }

    fun focusSliderPosition(): Float {
        val maxD = _ui.value.caps?.minFocusDistance ?: DeviceProfile.MAX_FOCUS_DIOPTER
        return diopterToSlider(_ui.value.controls.focusDiopter, maxD)
    }

    fun toggleAutoFocus() {
        val c = _ui.value.controls
        if (c.focusMode == ControlMode.AUTO) {
            selectFocus()
        } else {
            push(c.copy(focusMode = ControlMode.AUTO))
            // back to full-frame continuous AF, not the last tapped box
            controller.clearFocusRegion()
            _ui.value = _ui.value.copy(focusPoint = null)
        }
    }

    fun toggleAutoWb() {
        val c = _ui.value.controls
        if (c.wbMode == ControlMode.AUTO) push(anchorWhiteBalance(c))
        else push(c.copy(wbMode = ControlMode.AUTO))
    }

    fun cycleFlash() {
        val c = _ui.value.controls
        val next = when (c.flashMode) {
            FlashMode.OFF -> FlashMode.SINGLE
            FlashMode.SINGLE -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        push(c.copy(flashMode = next))
    }

    /**
     * One button, full cycle: OFF -> GRN -> RED -> WHT -> OFF. Cycling only the
     * colour once enabled left no way back to off.
     */
    fun cyclePeaking() {
        val c = _ui.value.controls
        if (!c.peakingEnabled) {
            push(c.copy(peakingEnabled = true, peakingColor = PeakColor.GREEN))
            return
        }
        val next = c.peakingColor.ordinal + 1
        if (next >= PeakColor.entries.size) push(c.copy(peakingEnabled = false))
        else push(c.copy(peakingColor = PeakColor.entries[next]))
    }

    fun toggleZebra() = push(_ui.value.controls.let { it.copy(zebraEnabled = !it.zebraEnabled) })

    fun toggleGrid() = push(_ui.value.controls.let { it.copy(gridEnabled = !it.gridEnabled) })

    fun toggleLevel() = push(_ui.value.controls.let { it.copy(levelEnabled = !it.levelEnabled) })

    fun toggleHistogram() = push(
        _ui.value.controls.let { it.copy(histogramEnabled = !it.histogramEnabled) },
    )

    fun toggleRawEnabled() {
        val caps = _ui.value.caps ?: return
        if (!caps.supportsRaw) {
            _ui.value = _ui.value.copy(notice = "RAW not available on camera ${caps.cameraId}")
            return
        }
        push(_ui.value.controls.let { it.copy(rawEnabled = !it.rawEnabled) })
    }

    fun toggleRgbHistogram() {
        _ui.value = _ui.value.copy(showRgbHistogram = !_ui.value.showRgbHistogram)
    }

    fun cyclePreviewRotation() {
        _ui.value = _ui.value.copy(
            previewRotationOffset = (_ui.value.previewRotationOffset + 90) % 360,
        )
    }

    fun toggleLock() {
        _ui.value = _ui.value.copy(locked = !_ui.value.locked)
    }

    fun setPeakingThreshold(v: Float) =
        push(_ui.value.controls.copy(peakingThreshold = v.coerceIn(0.02f, 0.6f)))

    fun setZebraThreshold(v: Float) =
        push(_ui.value.controls.copy(zebraThreshold = v.coerceIn(0.5f, 1f)))

    fun dismissNotice() {
        _ui.value = _ui.value.copy(notice = null)
    }

    // ------------------------------------------------------------------
    // shutter priority: manual time, servo'd ISO
    // ------------------------------------------------------------------

    /**
     * Camera2 has no shutter-priority mode — AE is either on (and owns both
     * time and gain) or off (and owns neither). S mode therefore runs AE off
     * with the user's shutter time and closes the loop on ISO itself, using the
     * mean luma of the analysis stream.
     */
    private fun runShutterPriorityServo() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastServoAt < 250) return
        lastServoAt = now

        val caps = _ui.value.caps ?: return
        val data = histogram.data.value
        if (data.samples == 0) return

        var weighted = 0L
        for (i in 0 until 256) weighted += data.luma[i].toLong() * i
        val mean = weighted.toDouble() / data.samples
        if (mean < 1.0) return

        val target = 108.0
        val ratio = (target / mean).coerceIn(0.5, 2.0)
        if (ratio in 0.92..1.08) return

        val current = _ui.value.controls.iso
        val next = (current * Math.pow(ratio, 0.6))
            .toInt()
            .coerceIn(caps.isoRange.lower, caps.isoRange.upper)
        if (next != current) setIso(next)
    }

    // ------------------------------------------------------------------
    // capture
    // ------------------------------------------------------------------

    fun capture() {
        if (_ui.value.busy || !_ui.value.ready) return
        val caps = _ui.value.caps ?: return
        _ui.value = _ui.value.copy(busy = true, lastSaved = null)

        viewModelScope.launch {
            val orientation = Camera2Controller.jpegOrientation(caps, deviceOrientation)
            val wantRaw = _ui.value.controls.rawEnabled
            val shot = controller.captureStill(orientation, wantRaw)

            if (shot.error != null) {
                Log.e(TAG, "capture error: ${shot.error}")
                shot.rawImage?.close()
                _ui.value = _ui.value.copy(busy = false, notice = shot.error)
                return@launch
            }

            val location = lastKnownLocation()
            val base = "PROCAM_${System.currentTimeMillis()}"

            val summary = withContext(Dispatchers.IO) {
                var jpegUri: android.net.Uri? = null
                var jpegSize = 0L
                var dngSize = 0L
                var dngUri: android.net.Uri? = null

                shot.jpegBytes?.let { bytes ->
                    val (u, s) = mediaWriter.writeJpeg(
                        bytes, shot.result, caps.focalLengthMm, caps.aperture, location, base,
                    )
                    jpegUri = u; jpegSize = s
                }

                val raw = shot.rawImage
                if (raw != null && shot.result != null) {
                    try {
                        val (u, s) = mediaWriter.writeDng(
                            raw,
                            shot.result,
                            controller.cameraCharacteristics(),
                            orientation,
                            location,
                            base,
                        )
                        dngUri = u; dngSize = s
                    } finally {
                        raw.close()
                    }
                } else {
                    raw?.close()
                }

                buildString {
                    append(base)
                    if (jpegUri != null) append("  JPG ${jpegSize / 1024}KB")
                    if (dngUri != null) append("  DNG ${dngSize / 1024 / 1024}MB")
                    append("  ${shot.wallClockMs}ms")
                }
            }

            Log.i(TAG, "saved: $summary")
            _ui.value = _ui.value.copy(busy = false, lastSaved = summary)
        }
    }

    private fun lastKnownLocation(): Location? {
        if (!_ui.value.controls.gpsEnabled) return null
        val app = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return try {
            val lm = app.getSystemService(android.content.Context.LOCATION_SERVICE)
                as LocationManager
            var best: Location? = null
            for (provider in lm.getProviders(true)) {
                val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                if (loc != null && (best == null || loc.time > best!!.time)) best = loc
            }
            best
        } catch (t: Throwable) {
            null
        }
    }
}
