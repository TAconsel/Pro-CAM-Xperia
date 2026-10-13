// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val TAG = "ProCam.Controller"

private const val TAG_MAIN = "main"
private const val TAG_DARK = "dark"


/**
 * Which output streams a session actually got. The device decides — LEVEL_3
 * only guarantees PRIV(preview) + PRIV(VGA) + JPEG(max) + RAW(max), and a YUV
 * analysis stream alongside RAW is not in any mandatory combination table, so
 * we degrade gracefully instead of assuming.
 */
data class StreamPlan(
    val label: String,
    val yuvSize: Size?,
    val jpeg: Boolean,
    val raw: Boolean,
    /** The JPEG size, from the RES choice. */
    val photo: PhotoSize,
) {
    val hasHistogram get() = yuvSize != null
}

data class ShotResult(
    val jpegBytes: ByteArray?,
    val rawImage: Image?,
    val result: TotalCaptureResult?,
    val requestedExposureNs: Long,
    val wallClockMs: Long,
    val error: String? = null,
    /** The underexposed RAW of an HDR bracket, and its metadata. */
    val darkRaw: Image? = null,
    val darkResult: TotalCaptureResult? = null,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

class Camera2Controller(context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread = HandlerThread("ProCam-Camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { cameraHandler.post(it) }

    private val analysisThread = HandlerThread("ProCam-Analysis").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null

    private var yuvReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private val captureMutex = Mutex()
    private var pendingShot: PendingShot? = null

    lateinit var capabilities: CameraCapabilities
        private set

    var streamPlan: StreamPlan? = null
        private set

    /** Latest metadata from the repeating request — drives the live readout. */
    val liveResult = MutableStateFlow<TotalCaptureResult?>(null)

    /** Set by the histogram engine. Called on the analysis thread. */
    var onAnalysisFrame: ((Image) -> Unit)? = null

    private var controls = ManualControlState()

    // ------------------------------------------------------------------
    // camera selection
    // ------------------------------------------------------------------

    /**
     * Only cameras the platform lets a normal app open. On A142P three of the
     * five devices are SYSTEM_CAMERA (the ultrawide among them) and are simply
     * absent from cameraIdList for us.
     */
    fun availableCameras(): List<CameraCapabilities> =
        CameraCapabilities.openableCameras(manager)

    fun bestCameraId(): String {
        val all = availableCameras()
        return all.firstOrNull { it.facingBack && it.supportsManualSensor && it.supportsRaw }?.cameraId
            ?: all.firstOrNull { it.facingBack }?.cameraId
            ?: all.firstOrNull()?.cameraId
            ?: DeviceProfile.MAIN_CAMERA_ID
    }

    // ------------------------------------------------------------------
    // open / close
    // ------------------------------------------------------------------

    /**
     * Completed once [open] has settled. The GL surface can become available
     * before the camera has finished opening, and startSession has to wait for
     * this rather than find a null device.
     */
    @Volatile
    private var opened = CompletableDeferred<Boolean>()

    /**
     * Raised when the session could not be built because the camera went away
     * underneath us — backgrounding, or the framework handing it to another
     * app. Distinct from a genuine configuration failure so the UI can stay
     * quiet instead of showing an error the user can do nothing about.
     */
    class CameraClosedException(message: String) : Exception(message)

    /** Called when an already-open camera is taken away. */
    var onDeviceLost: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    suspend fun open(cameraId: String) {
        check(device == null) { "camera already open" }
        if (opened.isCompleted) opened = CompletableDeferred()

        var openedDevice: CameraDevice? = null
        try {
            capabilities =
                CameraCapabilities.from(cameraId, manager.getCameraCharacteristics(cameraId))
            Log.i(TAG, "opening " + capabilities.summary())

            openedDevice = suspendCancellableCoroutine { cont ->
                val settled = AtomicBoolean(false)
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (settled.compareAndSet(false, true)) cont.resume(camera)
                        else camera.close()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        // Only the first callback answers the open; afterwards
                        // this is the framework reclaiming a live camera, which
                        // has to be unwound or the stale handle blocks reopening.
                        if (settled.compareAndSet(false, true)) cont.resume(null)
                        else handleDeviceLost(camera, "camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "camera error=$error")
                        camera.close()
                        if (settled.compareAndSet(false, true)) cont.resume(null)
                        else handleDeviceLost(camera, "camera error $error")
                    }
                }, cameraHandler)
            }
            device = openedDevice
        } finally {
            // Completed on every path, including a synchronous throw from
            // openCamera when another app holds the device — otherwise
            // startSession would sit on this deferred until it timed out.
            opened.complete(openedDevice != null)
        }

        if (openedDevice == null) error("could not open camera $cameraId")
    }

    private fun handleDeviceLost(camera: CameraDevice, reason: String) {
        if (device !== camera) return
        Log.w(TAG, "device lost: $reason")
        device = null
        runCatching { session?.close() }
        session = null
        closeReaders()
        streamPlan = null
        liveResult.value = null
        opened = CompletableDeferred()
        onDeviceLost?.invoke(reason)
    }

    /** True while a CameraDevice is held open by this controller. */
    fun isOpen(): Boolean = device != null

    fun close() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        closeReaders()
        streamPlan = null
        liveResult.value = null
        afRegions = null
        aeRegions = null
        opened = CompletableDeferred()
    }

    fun release() {
        close()
        cameraThread.quitSafely()
        analysisThread.quitSafely()
    }

    private fun closeReaders() {
        // The analysis reader is closed on its own thread: closing it from
        // here while the histogram is still reading a frame invalidates that
        // frame's buffer under it ("buffer is inaccessible"), which a session
        // rebuild for RES or HDR makes easy to hit.
        yuvReader?.let { r -> analysisHandler.post { runCatching { r.close() } } }
        runCatching { jpegReader?.close() }
        runCatching { rawReader?.close() }
        yuvReader = null
        jpegReader = null
        rawReader = null
    }

    // ------------------------------------------------------------------
    // session
    // ------------------------------------------------------------------

    private fun candidatePlans(caps: CameraCapabilities): List<StreamPlan> {
        val small = caps.availableAnalysisSizes.firstOrNull { it.width == 320 }
            ?: Size(320, 240)
        val photo = caps.photoSize(controls.photoSizeKey)

        return buildList {
            if (caps.supportsRaw) {
                add(StreamPlan("preview+YUV${caps.analysisSize.width}+JPEG+RAW", caps.analysisSize, true, true, photo))
                add(StreamPlan("preview+YUV${small.width}+JPEG+RAW", small, true, true, photo))
                add(StreamPlan("preview+JPEG+RAW", null, true, true, photo))
            }
            add(StreamPlan("preview+YUV${caps.analysisSize.width}+JPEG", caps.analysisSize, true, false, photo))
            add(StreamPlan("preview+JPEG", null, true, false, photo))
        }
    }

    /**
     * Tries stream combinations in descending order of usefulness and keeps the
     * first the HAL accepts. Returns the plan that stuck.
     */
    suspend fun startSession(preview: Surface): StreamPlan {
        val ok = withTimeoutOrNull(8_000) { opened.await() } ?: false
        if (!ok) error("camera did not open in time")
        val dev = device ?: error("camera not opened")
        previewSurface = preview

        var lastError: String? = null
        for (plan in candidatePlans(capabilities)) {
            if (device !== dev) {
                closeReaders()
                throw CameraClosedException("camera closed while configuring")
            }
            closeReaders()
            runCatching { session?.close() }
            session = null

            val targets = mutableListOf(preview)

            plan.yuvSize?.let { sz ->
                yuvReader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 3)
                    .also { r ->
                        r.setOnImageAvailableListener({ reader ->
                            // A frame can still be queued for a reader that has
                            // just been closed by a session rebuild.
                            val img = runCatching { reader.acquireLatestImage() }.getOrNull()
                                ?: return@setOnImageAvailableListener
                            try {
                                onAnalysisFrame?.invoke(img)
                            } finally {
                                img.close()
                            }
                        }, analysisHandler)
                        targets += r.surface
                    }
            }

            if (plan.jpeg) {
                val sz = plan.photo.size
                jpegReader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.JPEG, 2)
                    .also { r ->
                        r.setOnImageAvailableListener({ reader ->
                            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                            val shot = pendingShot
                            if (shot == null) {
                                img.close()
                            } else {
                                val bytes = img.planes[0].buffer.let { b ->
                                    ByteArray(b.remaining()).also { b.get(it) }
                                }
                                img.close()
                                shot.jpeg.trySend(bytes)
                            }
                        }, cameraHandler)
                        targets += r.surface
                    }
            }

            if (plan.raw) {
                val sz = capabilities.rawSize ?: DeviceProfile.RAW_SIZE
                rawReader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.RAW_SENSOR, 2)
                    .also { r ->
                        r.setOnImageAvailableListener({ reader ->
                            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                            val shot = pendingShot
                            if (shot == null) img.close() else shot.raw.trySend(img)
                        }, cameraHandler)
                        targets += r.surface
                    }
            }

            val configured = suspendCancellableCoroutine<CameraCaptureSession?> { cont ->
                val settled = AtomicBoolean(false)
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map { OutputConfiguration(it) },
                    cameraExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (settled.compareAndSet(false, true)) cont.resume(s)
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            runCatching { s.close() }
                            if (settled.compareAndSet(false, true)) cont.resume(null)
                        }
                    },
                )
                runCatching { dev.createCaptureSession(cfg) }.onFailure {
                    lastError = it.message
                    if (settled.compareAndSet(false, true)) cont.resume(null)
                }
            }

            if (configured != null) {
                session = configured
                streamPlan = plan
                Log.i(TAG, "session configured with plan: ${plan.label}")
                startPreview()
                return plan
            }

            Log.w(TAG, "stream plan rejected: ${plan.label}")
        }

        closeReaders()
        if (device !== dev) throw CameraClosedException("camera closed while configuring")
        error("no stream combination accepted by HAL (last=$lastError)")
    }

    // ------------------------------------------------------------------
    // repeating preview
    // ------------------------------------------------------------------

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            liveResult.value = result
        }
    }

    fun startPreview() {
        val dev = device ?: return
        val s = session ?: return
        val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { builder.addTarget(it) }
        yuvReader?.surface?.let { builder.addTarget(it) }
        builder.applyControls(controls, capabilities, DeviceProfile.PREVIEW_EXPOSURE_CAP_NS)
        builder.applyRegions()
        runCatching { s.setRepeatingRequest(builder.build(), previewCallback, cameraHandler) }
            .onFailure { Log.e(TAG, "setRepeatingRequest failed", it) }
    }

    // ------------------------------------------------------------------
    // touch AF / AE
    // ------------------------------------------------------------------

    private var afRegions: Array<MeteringRectangle>? = null
    private var aeRegions: Array<MeteringRectangle>? = null

    /**
     * A tapped region turns continuous AF into a one-shot AUTO cycle aimed at
     * that spot — the same behaviour as tapping the rear screen on a DSLR in
     * live view. Continuous AF would immediately wander off the subject again.
     */
    private fun CaptureRequest.Builder.applyRegions() {
        if (controls.focusMode != ControlMode.AUTO) return
        afRegions?.let {
            if (capabilities.maxAfRegions > 0) {
                set(CaptureRequest.CONTROL_AF_REGIONS, it)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            }
        }
        aeRegions?.let {
            if (capabilities.maxAeRegions > 0 && controls.exposureMode == ControlMode.AUTO) {
                set(CaptureRequest.CONTROL_AE_REGIONS, it)
            }
        }
    }

    /** @param sensorX/@param sensorY normalised point in the sensor's own frame. */
    fun focusAt(sensorX: Float, sensorY: Float, fromTap: Boolean = true) {
        if (device == null || session == null) return
        if (capabilities.maxAfRegions <= 0) return

        val arr = capabilities.activeArraySize
        if (arr.width() <= 0 || arr.height() <= 0) return

        val halfW = (arr.width() * 0.07f).toInt().coerceAtLeast(40)
        val halfH = (arr.height() * 0.07f).toInt().coerceAtLeast(40)
        val cx = (arr.left + sensorX.coerceIn(0f, 1f) * arr.width()).toInt()
        val cy = (arr.top + sensorY.coerceIn(0f, 1f) * arr.height()).toInt()
        val rect = android.graphics.Rect(
            (cx - halfW).coerceIn(arr.left, arr.right - 2),
            (cy - halfH).coerceIn(arr.top, arr.bottom - 2),
            (cx + halfW).coerceIn(arr.left + 1, arr.right),
            (cy + halfH).coerceIn(arr.top + 1, arr.bottom),
        )
        val region = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX))
        afRegions = region
        aeRegions = region
        regionFromTap = fromTap

        // repeating request first, so the new region is what the trigger acts on
        startPreview()
        triggerAfCycle()
    }

    /** True while the AF region is one the user tapped. */
    fun hasTappedRegion(): Boolean = afRegions != null && regionFromTap

    private var regionFromTap = false

    /**
     * Half-press of a two-stage shutter key: one AF cycle that then holds,
     * like AF-S on a camera body. It reuses the region from the last tap so a
     * half-press refocuses on the chosen subject; with none it focuses on the
     * centre. In AF_MODE_AUTO the lens stays put after the cycle until the
     * region is cleared, which is what makes the lock last until the shot.
     *
     * It is a real AF cycle on purpose. Pinning wherever continuous AF had
     * got to is faster, but continuous AF is not always right — closer than
     * the rated minimum focus it reports PASSIVE_FOCUSED while blurred — and
     * on the Xperia 1 VII a still captured while CONTINUOUS_PICTURE is
     * trigger-locked never returns a result and stalls the preview for good.
     */
    fun halfPressFocus() {
        if (hasTappedRegion()) triggerAfCycle() else focusAt(0.5f, 0.5f, fromTap = false)
    }

    private fun triggerAfCycle() {
        val dev = device ?: return
        val s = session ?: return

        fun trigger(value: Int) = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            previewSurface?.let { addTarget(it) }
            applyControls(controls, capabilities, DeviceProfile.PREVIEW_EXPOSURE_CAP_NS)
            applyRegions()
            set(CaptureRequest.CONTROL_AF_TRIGGER, value)
        }.build()

        runCatching {
            // Several HALs ignore a START that arrives while the previous AF
            // cycle is still active, which is why a second tap would appear to
            // do nothing. Cancel first, then trigger.
            s.capture(trigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL), null, cameraHandler)
            s.capture(trigger(CameraMetadata.CONTROL_AF_TRIGGER_START), previewCallback, cameraHandler)
        }.onFailure { Log.e(TAG, "AF trigger failed", it) }
    }

    /** Back to full-frame continuous AF. */
    fun clearFocusRegion() {
        if (afRegions == null && aeRegions == null) return
        afRegions = null
        aeRegions = null
        val dev = device
        val s = session
        if (dev != null && s != null) {
            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            builder.applyControls(controls, capabilities, DeviceProfile.PREVIEW_EXPOSURE_CAP_NS)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            runCatching { s.capture(builder.build(), null, cameraHandler) }
        }
        startPreview()
    }

    fun updateControls(newControls: ManualControlState) {
        val changed = newControls != controls
        controls = newControls
        if (changed) startPreview()
    }

    fun currentControls() = controls

    /** True when the preview is showing a shorter exposure than the user set. */
    fun previewIsSimulated(): Boolean =
        controls.exposureMode == ControlMode.MANUAL &&
            controls.exposureTimeNs > DeviceProfile.PREVIEW_EXPOSURE_CAP_NS

    // ------------------------------------------------------------------
    // still capture
    // ------------------------------------------------------------------

    private class PendingShot {
        val jpeg = Channel<ByteArray>(Channel.CONFLATED)

        // A conflated channel silently drops the superseded element. For Images
        // that means leaking a buffer the ImageReader can never hand out again,
        // and with maxImages=2 two leaks stall the camera — so anything dropped
        // or left over gets closed explicitly.
        val raw = Channel<Image>(Channel.UNLIMITED, onUndeliveredElement = { it.close() })
        val result = CompletableDeferred<TotalCaptureResult?>()
        val darkResult = CompletableDeferred<TotalCaptureResult?>()

        fun dispose() {
            jpeg.cancel()
            raw.cancel()
        }
    }

    /**
     * One shot, JPEG (+ RAW when the session got a RAW stream).
     *
     * The still request is built from the same [ManualControlState] as the
     * preview but WITHOUT the preview exposure cap, so a 32 s setting really
     * exposes for 32 s. Repeating requests are stopped first: interleaving a
     * multi-second capture with a preview stream makes the HAL's job ambiguous
     * and on some devices silently shortens the exposure.
     */
    /**
     * @param hdrBracketStops when above zero, a second RAW-only frame this
     *   many stops darker follows the shot in the same burst. Where the normal
     *   RAW clipped it still holds the highlights, which is what gives an
     *   Ultra HDR gain map real range rather than a guess.
     */
    suspend fun captureStill(jpegOrientation: Int, wantRaw: Boolean, hdrBracketStops: Int = 0): ShotResult =
        captureMutex.withLock {
            val dev = device ?: return ShotResult(null, null, null, 0, 0, "camera not open")
            val s = session ?: return ShotResult(null, null, null, 0, 0, "no session")
            val plan = streamPlan ?: return ShotResult(null, null, null, 0, 0, "no stream plan")

            val useRaw = wantRaw && plan.raw && rawReader != null
            val exposureNs = if (controls.exposureMode == ControlMode.MANUAL) {
                controls.exposureTimeNs.coerceIn(
                    capabilities.exposureTimeRange.lower,
                    capabilities.exposureTimeRange.upper,
                )
            } else {
                liveResult.value?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    ?: 16_666_666L
            }

            val longExposure = exposureNs > 500_000_000L
            if (longExposure) runCatching { s.stopRepeating() }

            val shot = PendingShot()
            pendingShot = shot

            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            jpegReader?.surface?.let { builder.addTarget(it) }
            if (useRaw) rawReader?.surface?.let { builder.addTarget(it) }
            builder.applyControls(controls, capabilities, previewCap = null)
            // Without the region the still request goes back to
            // CONTINUOUS_PICTURE, and the lens can leave the focus that a tap
            // or half-press just locked.
            builder.applyRegions()
            builder.applyStillFlash(controls, capabilities)
            builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)

            builder.setTag(TAG_MAIN)

            // The dark frame: same ISO and focus, a fraction of the exposure,
            // RAW only. Taken from the preview's metering in the auto modes,
            // which is what the shot itself is about to use.
            val bracket = hdrBracketStops > 0 && useRaw
            val dark = if (bracket) {
                val iso = if (controls.exposureMode == ControlMode.MANUAL) {
                    controls.iso
                } else {
                    liveResult.value?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 400
                }.coerceIn(capabilities.isoRange.lower, capabilities.isoRange.upper)
                val darkNs = (exposureNs shr hdrBracketStops)
                    .coerceIn(capabilities.exposureTimeRange.lower, capabilities.exposureTimeRange.upper)
                // A preview-type request, not a still: on the Xperia 1 VII a
                // still capture's RAW comes from the zero-shutter-lag buffer
                // at the normal exposure, whatever the request asks for, while
                // its metadata reports the dark settings. A preview request is
                // a frame actually exposed with them.
                dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    rawReader?.surface?.let { addTarget(it) }
                    applyControls(controls, capabilities, previewCap = null)
                    applyRegions()
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, darkNs)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, darkNs)
                    if (capabilities.hasFlash) set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                    setTag(TAG_DARK)
                }.build()
            } else {
                null
            }

            val startedAt = System.currentTimeMillis()

            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    cs: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (request.tag == TAG_DARK) shot.darkResult.complete(result)
                    else shot.result.complete(result)
                }

                override fun onCaptureFailed(
                    cs: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    Log.e(TAG, "capture failed reason=${failure.reason} tag=${request.tag}")
                    if (request.tag == TAG_DARK) shot.darkResult.complete(null)
                    else shot.result.complete(null)
                }
            }

            runCatching {
                if (dark != null) {
                    s.captureBurst(listOf(builder.build(), dark), callback, cameraHandler)
                } else {
                    s.capture(builder.build(), callback, cameraHandler)
                }
            }.onFailure {
                pendingShot = null
                shot.dispose()
                if (longExposure) startPreview()
                return ShotResult(null, null, null, exposureNs, 0, "capture() threw: ${it.message}")
            }

            // Generous slack over the exposure itself: the HAL still has to run
            // its ISP pipeline and encode a 12.5 MP JPEG afterwards.
            val budgetMs = exposureNs / 1_000_000 + 20_000

            val result = withTimeoutOrNull(budgetMs) { shot.result.await() }
            val jpegBytes = withTimeoutOrNull(budgetMs) { shot.jpeg.receive() }
            val darkResult = if (dark != null) withTimeoutOrNull(budgetMs) { shot.darkResult.await() } else null

            // Each RAW buffer belongs to the request whose sensor timestamp it
            // carries; the burst does not promise which arrives first.
            var rawImage: Image? = null
            var darkRaw: Image? = null
            val expectRaws = if (!useRaw) 0 else if (dark != null) 2 else 1
            repeat(expectRaws) {
                val img = withTimeoutOrNull(budgetMs) { shot.raw.receive() } ?: return@repeat
                val ts = img.timestamp
                when {
                    dark != null && darkResult?.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) == ts &&
                        darkRaw == null -> darkRaw = img
                    rawImage == null -> rawImage = img
                    darkRaw == null -> darkRaw = img
                    else -> img.close()
                }
            }

            pendingShot = null
            shot.dispose() // closes any late or superseded RAW buffer
            val elapsed = System.currentTimeMillis() - startedAt
            if (longExposure) startPreview()

            val error = when {
                result == null -> "no TotalCaptureResult within ${budgetMs}ms"
                jpegBytes == null -> "no JPEG within ${budgetMs}ms"
                useRaw && rawImage == null -> "no RAW within ${budgetMs}ms"
                else -> null
            }

            if (error != null) darkRaw?.close()
            ShotResult(
                jpegBytes, rawImage, result, exposureNs, elapsed, error,
                darkRaw = if (error == null) darkRaw else null,
                darkResult = darkResult,
            )
        }

    internal fun cameraCharacteristics() =
        manager.getCameraCharacteristics(capabilities.cameraId)

    companion object {
        /**
         * Device orientation from OrientationEventListener (degrees clockwise
         * from natural) folded together with the sensor mounting angle.
         */
        fun jpegOrientation(caps: CameraCapabilities, deviceOrientationDeg: Int): Int {
            if (deviceOrientationDeg == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) {
                return caps.sensorOrientation
            }
            val rounded = (deviceOrientationDeg + 45) / 90 * 90
            val signed = if (caps.facingBack) rounded else -rounded
            return (caps.sensorOrientation + signed + 360) % 360
        }
    }
}
