// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.ui

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.procam.camera.ControlMode
import com.procam.camera.FlashMode
import com.procam.camera.IsoScale
import com.procam.camera.ShutterScale
import com.procam.camera.diopterToLabel
import com.procam.camera.formatEv
import com.procam.camera.formatShutter
import com.procam.camera.sliderToDiopter
import com.procam.gl.CameraGLSurfaceView
import com.procam.sensor.LevelSensor
import com.procam.vm.CameraViewModel
import com.procam.vm.ScrubTarget
import com.procam.vm.ShootMode

/** Tint in the usual camera notation: G = green shift, M = magenta shift. */
private fun tintLabel(steps: Int): String = when {
    steps == 0 -> "0"
    steps < 0 -> "G${-steps}"
    else -> "M$steps"
}

@Composable
fun CameraScreen(
    vm: CameraViewModel,
    onRequestLocationPermission: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val overlay by vm.overlay.collectAsState()
    val hist by vm.histogram.data.collectAsState()

    // Recomputed on configuration change so flipping the phone end-for-end in
    // landscape keeps the preview upright.
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val surfaceRotation = rememberSurfaceRotation(view, configuration)
    val displayDegrees = when (surfaceRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    val context = LocalContext.current

    // GLSurfaceView will not survive a trip to another app unless the host
    // forwards these, and the camera must be handed back while we are away so
    // the framework can give it to whoever is in front.
    var glView by remember { mutableStateOf<CameraGLSurfaceView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glView?.onResume()
                    vm.onEnterForeground()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    glView?.onPause()
                    vm.onEnterBackground()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val levelSensor = remember { LevelSensor(context) }
    val level by levelSensor.state.collectAsState()
    levelSensor.displayRotation = surfaceRotation
    DisposableEffect(ui.controls.levelEnabled) {
        if (ui.controls.levelEnabled) levelSensor.start()
        onDispose { levelSensor.stop() }
    }

    Box(Modifier.fillMaxSize().background(ProTheme.Black)) {
        when {
            ui.fatal != null -> FatalPane(ui.fatal!!)
            ui.opening -> CenterText("OPENING CAMERA…")
            else -> Row(Modifier.fillMaxSize()) {
                LeftRail(
                    vm = vm,
                    mode = ui.mode,
                    focalMm = ui.caps?.focalLengthMm ?: 0f,
                    rotationOffset = ui.previewRotationOffset,
                    cameraId = ui.caps?.cameraId ?: "-",
                    cameraCount = ui.cameras.size,
                    onOpenDiagnostics = onOpenDiagnostics,
                )

                val caps = ui.caps
                val previewRotation = if (caps == null) {
                    0
                } else {
                    // Documented relationship between the sensor mounting angle
                    // and the window. Any device that disagrees is corrected by
                    // the per-model offset rather than by bending this formula.
                    val b = if (caps.facingBack) {
                        (caps.sensorOrientation - displayDegrees + 360) % 360
                    } else {
                        (caps.sensorOrientation + displayDegrees) % 360
                    }
                    (b + ui.previewRotationOffset + 360) % 360
                }

                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(4f / 3f)
                        .background(Color.Black)
                        .pointerInput(caps, previewRotation) {
                            detectTapGestures { pos ->
                                val c = caps ?: return@detectTapGestures
                                vm.tapToFocus(
                                    nx = (pos.x / size.width).coerceIn(0f, 1f),
                                    ny = (pos.y / size.height).coerceIn(0f, 1f),
                                    previewRotation = previewRotation,
                                    mirrored = !c.facingBack,
                                )
                            }
                        },
                ) {
                    if (caps != null) {
                        val rotation = previewRotation
                        LaunchedEffect(displayDegrees, caps.sensorOrientation, rotation) {
                            android.util.Log.i(
                                "ProCam.Rot",
                                "displayDegrees=$displayDegrees sensorOrientation=" +
                                    "${caps.sensorOrientation} facingBack=${caps.facingBack} " +
                                    "offset=${ui.previewRotationOffset} " +
                                    "final=$rotation buffer=${caps.previewSize}",
                            )
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                CameraGLSurfaceView(ctx).apply {
                                    init(caps.previewSize) { st -> vm.onSurfaceTextureReady(st) }
                                    glView = this
                                }
                            },
                            update = { v ->
                                v.previewRenderer.overlay = overlay
                                v.previewRenderer.rotationDegrees = rotation
                                v.previewRenderer.mirror = !caps.facingBack
                            },
                            // Leaving this view behind without releasing the
                            // SurfaceTexture keeps a camera-facing buffer queue
                            // alive across a trip to the diagnostics screen.
                            onRelease = { v ->
                                if (glView === v) glView = null
                                runCatching { v.previewRenderer.releaseSurfaceTexture() }
                            },
                        )
                    }

                    if (ui.controls.gridEnabled) GridOverlay()

                    if (ui.controls.levelEnabled && level.available) {
                        LevelOverlay(
                            rollDeg = level.rollDeg,
                            pitchDeg = level.pitchDeg,
                            isLevel = level.isLevel,
                        )
                    }

                    ui.focusPoint?.let { fp ->
                        FocusReticle(fp.x, fp.y, ui.afState)
                        LaunchedEffect(fp.stamp) {
                            kotlinx.coroutines.delay(2200)
                            vm.clearFocusPoint()
                        }
                    }

                    PreviewTopStrip(vm, Modifier.align(Alignment.TopStart))
                    PreviewBottomStrip(vm, level.rollDeg, Modifier.align(Alignment.BottomStart))

                    if (ui.controls.histogramEnabled && ui.plan?.hasHistogram == true) {
                        HistogramView(
                            data = hist,
                            showRgb = ui.showRgbHistogram,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 10.dp, bottom = 34.dp)
                                .width(150.dp)
                                .height(64.dp)
                                .clickable { vm.toggleRgbHistogram() },
                        )
                    }

                    if (ui.previewSimulated) {
                        Text2(
                            "PREVIEW SIMULATED · ${formatShutter(ui.controls.exposureTimeNs)} ON CAPTURE",
                            ProTheme.Accent,
                            10.sp,
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 26.dp, end = 8.dp)
                                .background(Color(0xAA000000))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }

                ControlDeck(vm, onRequestLocationPermission, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun rememberSurfaceRotation(
    view: android.view.View,
    @Suppress("UNUSED_PARAMETER") configuration: android.content.res.Configuration,
): Int = view.display?.rotation ?: Surface.ROTATION_0

// ---------------------------------------------------------------------------
// left rail
// ---------------------------------------------------------------------------

@Composable
private fun LeftRail(
    vm: CameraViewModel,
    mode: ShootMode,
    focalMm: Float,
    rotationOffset: Int,
    cameraId: String,
    cameraCount: Int,
    onOpenDiagnostics: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(58.dp)
            .background(ProTheme.Black)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text2(
                "DIAG",
                ProTheme.TextSecondary,
                10.sp,
                Modifier.clickable { onOpenDiagnostics() }.padding(6.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text2(
                "CAM $cameraId",
                if (cameraCount > 1) ProTheme.Accent else ProTheme.TextDim,
                10.sp,
                Modifier.clickable(enabled = cameraCount > 1) { vm.switchCamera() }.padding(6.dp),
            )
        }

        ModeDial(
            modes = listOf("P", "S", "M", "MR"),
            selected = mode.name,
            onSelect = { vm.setMode(ShootMode.valueOf(it)) },
        )

        // Tapping the lens block cycles the preview rotation. The right value is
        // device-specific glue between SENSOR_ORIENTATION and the window, and
        // the only way to pin it down is to look at it.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { vm.cyclePreviewRotation() },
        ) {
            Text2("Lens", ProTheme.TextDim, 9.sp)
            Text2("${focalMm.toInt()}mm", ProTheme.TextPrimary, 11.sp)
            Text2(
                "ROT ${if (rotationOffset == 0) "0" else "+$rotationOffset"}",
                if (rotationOffset == 0) ProTheme.TextDim else ProTheme.Accent,
                9.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// strips over the preview
// ---------------------------------------------------------------------------

@Composable
private fun PreviewTopStrip(vm: CameraViewModel, modifier: Modifier) {
    val ui by vm.ui.collectAsState()
    val caps = ui.caps ?: return
    val raw = if (ui.controls.rawEnabled && caps.supportsRaw) "RAW+JPEG" else "JPEG"
    val mp = caps.jpegSize?.let {
        "%.1fMP".format(it.width.toLong() * it.height / 1_000_000.0)
    } ?: "-"

    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0x66000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text2("cam ${caps.cameraId}", ProTheme.TextSecondary, 10.sp)
        Text2(caps.hardwareLevelName, ProTheme.TextSecondary, 10.sp)
        Text2(mp, ProTheme.TextPrimary, 10.sp)
        Text2(raw, if (raw.startsWith("RAW")) ProTheme.Accent else ProTheme.TextPrimary, 10.sp)
        Spacer(Modifier.weight(1f))
        ui.plan?.let { Text2(it.label, ProTheme.TextDim, 9.sp) }
    }
}

@Composable
private fun PreviewBottomStrip(vm: CameraViewModel, rollDeg: Float, modifier: Modifier) {
    val ui by vm.ui.collectAsState()
    val caps = ui.caps
    val shutter = ui.liveExposureNs?.let(::formatShutter) ?: "--"
    val iso = ui.liveIso?.toString() ?: "--"
    val f = ui.liveAperture ?: caps?.aperture ?: 0f
    val ev = formatEv(ui.controls.evSteps, caps?.evStep ?: 1.0)
    val focus = if (ui.controls.focusMode == ControlMode.MANUAL) {
        "MF ${diopterToLabel(ui.controls.focusDiopter)}"
    } else {
        "AF-C"
    }

    Column(modifier.fillMaxWidth()) {
        ui.lastSaved?.let {
            Text2(
                it,
                ProTheme.Ok,
                9.sp,
                Modifier
                    .background(Color(0xAA000000))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x88000000))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text2(ui.mode.name, ProTheme.Accent, ProTheme.ReadoutSize, weight = FontWeight.Bold)
            Text2(shutter, ProTheme.TextPrimary, ProTheme.ReadoutSize, weight = FontWeight.Medium)
            Text2("f/${"%.2f".format(f)}", ProTheme.TextPrimary, ProTheme.ReadoutSize)
            Text2(ev, ProTheme.TextPrimary, ProTheme.ReadoutSize)
            Text2("ISO $iso", ProTheme.TextPrimary, ProTheme.ReadoutSize)
            Text2(focus, ProTheme.TextSecondary, ProTheme.ReadoutSize)
            if (ui.controls.levelEnabled) {
                Spacer(Modifier.weight(1f))
                Text2(
                    "%+.1f°".format(rollDeg),
                    if (kotlin.math.abs(rollDeg) <= 1f) ProTheme.Ok else ProTheme.TextSecondary,
                    ProTheme.ReadoutSize,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// right control deck
// ---------------------------------------------------------------------------

@Composable
private fun ControlDeck(
    vm: CameraViewModel,
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier,
) {
    val ui by vm.ui.collectAsState()
    val caps = ui.caps
    val c = ui.controls
    val manual = c.exposureMode == ControlMode.MANUAL
    val on = !ui.locked

    Column(
        modifier
            .fillMaxHeight()
            .background(ProTheme.Panel)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // ---- the single adjustment ruler ---------------------------------
        when (ui.scrubTarget) {
            ScrubTarget.EV -> {
                val lo = caps?.evRange?.lower ?: -24
                val hi = caps?.evRange?.upper ?: 24
                val step = caps?.evStep ?: (1.0 / 6.0)
                val values = (lo..hi).toList()
                ValueRuler(
                    title = "EXPOSURE COMP",
                    values = values,
                    index = (c.evSteps - lo).coerceIn(0, values.lastIndex),
                    format = { formatEv(it, step) },
                    enabled = on && !manual,
                    // label every half stop; a full stop is too far apart to
                    // put a number on screen at this panel width
                    majorEvery = ((1.0 / step) / 2).toInt().coerceAtLeast(1),
                    onIndexChange = { vm.setEv(values[it]) },
                )
            }

            ScrubTarget.ISO -> {
                val values = vm.isoValues()
                ValueRuler(
                    title = "ISO",
                    values = values,
                    index = IsoScale.nearestIndex(values, c.iso),
                    format = { it.toString() },
                    enabled = on && manual,
                    majorEvery = 3,
                    onIndexChange = { vm.setIso(values[it]) },
                )
            }

            ScrubTarget.SHUTTER -> {
                val values = vm.shutterValues()
                ValueRuler(
                    title = "SHUTTER",
                    values = values,
                    index = ShutterScale.nearestIndex(values, c.exposureTimeNs),
                    format = ::formatShutter,
                    enabled = on && manual,
                    majorEvery = 3,
                    onIndexChange = { vm.setShutter(values[it]) },
                )
            }

            ScrubTarget.WB -> {
                val values = vm.kelvinValues()
                ValueRuler(
                    title = "WHITE BALANCE",
                    values = values,
                    index = values.indexOfFirst { it >= c.wbKelvin }.coerceAtLeast(0),
                    format = { "${it}K" },
                    enabled = on,
                    majorEvery = 2,
                    onIndexChange = { vm.setKelvin(values[it]) },
                )
            }

            ScrubTarget.TINT -> {
                val values = vm.tintSteps()
                ValueRuler(
                    title = "TINT",
                    values = values,
                    index = (kotlin.math.round(c.wbTint * 10).toInt() + 10)
                        .coerceIn(0, values.lastIndex),
                    format = ::tintLabel,
                    enabled = on,
                    majorEvery = 5,
                    onIndexChange = { vm.setTint(values[it] / 10f) },
                )
            }

            ScrubTarget.FOCUS -> {
                val values = vm.focusValues()
                val maxD = caps?.minFocusDistance ?: 20f
                ValueRuler(
                    title = "FOCUS DISTANCE",
                    values = values,
                    index = (vm.focusSliderPosition() * (values.size - 1)).toInt()
                        .coerceIn(0, values.lastIndex),
                    format = { diopterToLabel(sliderToDiopter(it, maxD)) },
                    enabled = on && (caps?.supportsManualFocus ?: false),
                    majorEvery = 4,
                    onIndexChange = { vm.setFocusSlider(values[it]) },
                )
            }

            ScrubTarget.PEAK -> {
                val values = (2..40).map { it / 100f }
                ValueRuler(
                    title = "PEAKING LEVEL",
                    values = values,
                    index = values.indexOfFirst { it >= c.peakingThreshold }.coerceAtLeast(0),
                    format = { "%.2f".format(it) },
                    enabled = on,
                    majorEvery = 5,
                    onIndexChange = { vm.setPeakingThreshold(values[it]) },
                )
            }

            ScrubTarget.ZEBRA -> {
                val values = (70..100).map { it / 100f }
                ValueRuler(
                    title = "ZEBRA IRE",
                    values = values,
                    index = values.indexOfFirst { it >= c.zebraThreshold }.coerceAtLeast(0),
                    format = { "${(it * 100).toInt()}" },
                    enabled = on,
                    majorEvery = 5,
                    onIndexChange = { vm.setZebraThreshold(values[it]) },
                )
            }
        }

        // ---- AF-ON / AWB ---------------------------------------------------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(
                "AF-ON",
                active = c.focusMode == ControlMode.AUTO,
                enabled = on && (caps?.supportsManualFocus ?: false),
                modifier = Modifier.weight(1f),
            ) { vm.toggleAutoFocus() }
            ActionButton(
                "AWB",
                active = c.wbMode == ControlMode.AUTO,
                enabled = on,
                modifier = Modifier.weight(1f),
            ) { vm.toggleAutoWb() }
        }

        // ---- button grid ---------------------------------------------------
        val cell = Modifier.weight(1f)
        val gap = Arrangement.spacedBy(4.dp)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            DeckButton(
                "ISO", if (manual) c.iso.toString() else "AUTO",
                selected = ui.scrubTarget == ScrubTarget.ISO, enabled = on, modifier = cell,
            ) { vm.selectIso() }
            DeckButton(
                "SS", if (ui.mode == ShootMode.P) "AUTO" else formatShutter(c.exposureTimeNs),
                selected = ui.scrubTarget == ScrubTarget.SHUTTER, enabled = on, modifier = cell,
            ) { vm.selectShutter() }
            DeckButton(
                "EV", formatEv(c.evSteps, caps?.evStep ?: 1.0),
                selected = ui.scrubTarget == ScrubTarget.EV,
                enabled = on && !manual, // EV compensation is meaningless with AE off
                modifier = cell,
            ) { vm.setScrubTarget(ScrubTarget.EV) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            DeckButton(
                "WB", if (c.wbMode == ControlMode.AUTO) "AWB" else "${c.wbKelvin}K",
                selected = ui.scrubTarget == ScrubTarget.WB, enabled = on, modifier = cell,
            ) { vm.selectWb() }
            DeckButton(
                "TINT", tintLabel(kotlin.math.round(c.wbTint * 10).toInt()),
                selected = ui.scrubTarget == ScrubTarget.TINT, enabled = on, modifier = cell,
            ) { vm.selectTint() }
            DeckButton(
                "FOCUS",
                if (c.focusMode == ControlMode.AUTO) "AF-C" else diopterToLabel(c.focusDiopter),
                selected = ui.scrubTarget == ScrubTarget.FOCUS,
                enabled = on && (caps?.supportsManualFocus ?: false),
                modifier = cell,
            ) { vm.selectFocus() }
        }
        // Long-press opens the fine level for an overlay, so the deck keeps one
        // button per feature instead of two.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            DeckButton(
                "PEAK", if (c.peakingEnabled) c.peakingColor.label else "OFF",
                selected = c.peakingEnabled, enabled = on, modifier = cell,
                onLongClick = { vm.setScrubTarget(ScrubTarget.PEAK) },
            ) { vm.cyclePeaking() }
            DeckButton(
                "ZEBRA", if (c.zebraEnabled) "${(c.zebraThreshold * 100).toInt()}" else "OFF",
                selected = c.zebraEnabled, enabled = on, modifier = cell,
                onLongClick = { vm.setScrubTarget(ScrubTarget.ZEBRA) },
            ) { vm.toggleZebra() }
            DeckButton(
                "HIST",
                if (c.histogramEnabled) (if (ui.showRgbHistogram) "RGB" else "LUM") else "OFF",
                selected = c.histogramEnabled, enabled = on, modifier = cell,
                onLongClick = { vm.toggleRgbHistogram() },
            ) { vm.toggleHistogram() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            DeckButton(
                "GRID", if (c.gridEnabled) "3x3" else "OFF",
                selected = c.gridEnabled, enabled = on, modifier = cell,
            ) { vm.toggleGrid() }
            DeckButton(
                "LEVEL", if (c.levelEnabled) "ON" else "OFF",
                selected = c.levelEnabled, enabled = on, modifier = cell,
            ) { vm.toggleLevel() }
            DeckButton(
                "FLASH",
                when (c.flashMode) {
                    FlashMode.OFF -> "OFF"
                    FlashMode.SINGLE -> "ON"
                    FlashMode.TORCH -> "TORCH"
                },
                selected = c.flashMode != FlashMode.OFF,
                enabled = on && (caps?.hasFlash ?: false),
                modifier = cell,
            ) { vm.cycleFlash() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            DeckButton(
                "FMT", if (c.rawEnabled) "RAW+J" else "JPEG",
                selected = c.rawEnabled,
                enabled = on && (caps?.supportsRaw ?: false),
                modifier = cell,
            ) { vm.toggleRawEnabled() }
            DeckButton(
                "GPS", if (c.gpsEnabled) "ON" else "OFF",
                selected = c.gpsEnabled, enabled = on, modifier = cell,
            ) { vm.toggleGps(onRequestLocationPermission) }
            DeckButton(
                "MR", if (ui.mrPresent) "SET" else "EMPTY",
                selected = ui.mode == ShootMode.MR, enabled = on, modifier = cell,
                onLongClick = { vm.saveMemoryRecall() },
            ) { vm.setMode(ShootMode.MR) }
        }

        ui.notice?.let {
            Text2(
                it,
                ProTheme.Warn,
                9.sp,
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.dismissNotice() }
                    .padding(vertical = 2.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // ---- lock + shutter -------------------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.clickable { vm.toggleLock() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(width = 26.dp, height = 13.dp)
                        .background(
                            if (ui.locked) ProTheme.Accent else ProTheme.ChipBorder,
                            RoundedCornerShape(7.dp),
                        ),
                )
                Spacer(Modifier.width(5.dp))
                Text2("Lock", if (ui.locked) ProTheme.Accent else ProTheme.TextSecondary, 10.sp)
            }

            ShutterButton(
                busy = ui.busy,
                enabled = ui.ready,
                rawArmed = c.rawEnabled && (caps?.supportsRaw ?: false),
                onClick = { vm.capture() },
            )
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun FatalPane(message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text2("CAMERA ERROR", ProTheme.Warn, 16.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text2(message, ProTheme.TextPrimary, 12.sp)
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text2(text, ProTheme.TextSecondary, 13.sp)
    }
}

@Composable
internal fun Text2(
    text: String,
    color: Color,
    size: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
) {
    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontSize = size,
        fontFamily = ProTheme.Mono,
        fontWeight = weight,
        modifier = modifier,
        maxLines = 2,
    )
}
