// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procam.analysis.HistogramData
import kotlin.math.abs

/**
 * A control-deck button: small caption on top, current value underneath.
 * Selected buttons turn amber, which is also what routes the ruler.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckButton(
    label: String,
    value: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val fg = when {
        !enabled -> ProTheme.TextDim
        selected -> ProTheme.Accent
        else -> ProTheme.TextPrimary
    }
    Column(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Color(0x1AFFA028) else ProTheme.Chip)
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) ProTheme.Accent else ProTheme.ChipBorder,
                shape = RoundedCornerShape(3.dp),
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            color = if (enabled) ProTheme.TextSecondary else ProTheme.TextDim,
            fontSize = ProTheme.LabelSize,
            fontFamily = ProTheme.Mono,
            letterSpacing = 0.5.sp,
            maxLines = 1,
        )
        Text(
            value,
            color = fg,
            fontSize = ProTheme.ValueSize,
            fontFamily = ProTheme.Mono,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Outlined action button — AF-ON / AWB. */
@Composable
fun ActionButton(
    text: String,
    active: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(if (active) Color(0x26FFA028) else Color(0xFF141414))
            .border(
                0.8.dp,
                if (active) ProTheme.Accent else ProTheme.ChipBorder,
                RoundedCornerShape(2.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                !enabled -> ProTheme.TextDim
                active -> ProTheme.Accent
                else -> ProTheme.TextPrimary
            },
            fontSize = 11.sp,
            fontFamily = ProTheme.Mono,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The one adjustment control the whole deck uses — EV, ISO, shutter, Kelvin,
 * focus, peaking level and zebra level all ride on it.
 *
 * It behaves like the engraved scale on a lens barrel: the scale slides under a
 * fixed amber index mark, neighbouring values stay readable so you can see what
 * is coming, and every detent fires a haptic tick so the control can be worked
 * without looking away from the frame. The entire strip is the drag target.
 */
@Composable
fun <T> ValueRuler(
    title: String,
    values: List<T>,
    index: Int,
    format: (T) -> String,
    enabled: Boolean,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    majorEvery: Int = 5,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = remember(context) { HapticEngine(context) }

    // 22.dp keeps roughly seven detents either side of the index mark inside
    // the deck's width, which is what makes the major-tick labels land on
    // screen instead of off the edge.
    val stepPx = with(density) { 22.dp.toPx() }
    val labelPx = with(density) { 9.sp.toPx() }
    val safeIndex = index.coerceIn(0, (values.size - 1).coerceAtLeast(0))

    // Sub-detent drag distance. The scale is drawn shifted by this, so it
    // tracks the finger continuously instead of stepping a whole tick at a
    // time, then eases back onto the detent when the finger lifts.
    var residual by remember { mutableFloatStateOf(0f) }

    // The gesture must not be re-keyed on the index: doing so tears down the
    // detector the moment the value changes and cancels the drag in progress.
    val liveIndex by rememberUpdatedState(safeIndex)
    val liveEnabled by rememberUpdatedState(enabled)
    val lastIdx = values.lastIndex
    val onChange by rememberUpdatedState(onIndexChange)

    val tickPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }

    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            color = if (enabled) ProTheme.Accent else ProTheme.TextDim,
            fontSize = ProTheme.LabelSize,
            fontFamily = ProTheme.Mono,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            values.getOrNull(safeIndex)?.let(format) ?: "--",
            color = if (enabled) ProTheme.Accent else ProTheme.TextDim,
            fontSize = ProTheme.ScrubActiveSize,
            fontFamily = ProTheme.Mono,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(lastIdx) {
                    val settle: () -> Unit = {
                        scope.launch {
                            animate(
                                initialValue = residual,
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) { v, _ -> residual = v }
                        }
                    }
                    detectHorizontalDragGestures(
                        onDragEnd = settle,
                        onDragCancel = settle,
                    ) { change, drag ->
                        if (!liveEnabled) return@detectHorizontalDragGestures
                        change.consume()
                        residual -= drag
                        var current = liveIndex
                        while (abs(residual) >= stepPx) {
                            val dir = if (residual > 0) 1 else -1
                            val next = current + dir
                            if (next !in 0..lastIdx) {
                                // rubber-band at the ends instead of running away
                                residual = residual.coerceIn(-stepPx * 0.6f, stepPx * 0.6f)
                                haptic.endStop()
                                break
                            }
                            residual -= dir * stepPx
                            current = next
                            haptic.detent(major = next % majorEvery == 0)
                            onChange(next)
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val baseline = h * 0.40f
            val visible = (w / stepPx / 2f).toInt() + 2

            for (offset in -visible..visible) {
                val idx = safeIndex + offset
                if (idx !in values.indices) continue
                val x = cx + offset * stepPx - residual
                if (x < -stepPx || x > w + stepPx) continue
                val major = idx % majorEvery == 0
                val centre = offset == 0
                val len = when {
                    centre -> h * 0.42f
                    major -> h * 0.28f
                    else -> h * 0.14f
                }
                val fade = (1f - abs(offset) / (visible + 1f)).coerceIn(0.15f, 1f)
                drawLine(
                    color = when {
                        !enabled -> ProTheme.TextDim.copy(alpha = fade)
                        centre -> ProTheme.Accent
                        major -> ProTheme.TextPrimary.copy(alpha = fade)
                        else -> ProTheme.TextSecondary.copy(alpha = fade * 0.7f)
                    },
                    start = Offset(x, baseline - len / 2f),
                    end = Offset(x, baseline + len / 2f),
                    strokeWidth = if (centre) 3f else if (major) 1.6f else 1f,
                )

                // Only major detents carry a number; anything denser turns into
                // unreadable mush at this panel width.
                if (major && !centre) {
                    val label = values.getOrNull(idx)?.let(format) ?: continue
                    tickPaint.textSize = labelPx
                    val alpha = if (enabled) (fade * 205).toInt() else 70
                    tickPaint.color =
                        android.graphics.Color.argb(alpha.coerceIn(0, 255), 154, 154, 154)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(label, x, h * 0.93f, tickPaint)
                    }
                }
            }

            // fixed index mark above the centre detent
            val marker = Path().apply {
                moveTo(cx, baseline - h * 0.24f)
                lineTo(cx - h * 0.10f, baseline - h * 0.42f)
                lineTo(cx + h * 0.10f, baseline - h * 0.42f)
                close()
            }
            drawPath(marker, if (enabled) ProTheme.Accent else ProTheme.TextDim)

            // edge fades so the scale reads as continuous rather than clipped
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to ProTheme.Panel,
                    0.13f to Color.Transparent,
                    0.87f to Color.Transparent,
                    1f to ProTheme.Panel,
                ),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            if (enabled) ProTheme.AccentDim else Color.Transparent,
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

/**
 * Live histogram. Luma is filled; R/G/B ride on top as thin curves so channel
 * clipping stays visible even when overall luma looks safe.
 */
@Composable
fun HistogramView(
    data: HistogramData,
    showRgb: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bins = 256
        val bw = w / bins
        val peak = data.peak.toFloat()

        drawRect(color = Color(0xB3000000))

        val lumaPath = Path().apply {
            moveTo(0f, h)
            for (i in 0 until bins) {
                val v = (data.luma[i] / peak).coerceIn(0f, 1f)
                lineTo(i * bw, h - v * h)
            }
            lineTo(w, h)
            close()
        }
        drawPath(lumaPath, Color(0xCCFFFFFF))

        if (showRgb) {
            val channels = listOf(
                data.r to Color(0xCCFF4040),
                data.g to Color(0xCC40FF60),
                data.b to Color(0xCC4080FF),
            )
            for ((arr, color) in channels) {
                var prevX = 0f
                var prevY = h - (arr[0] / peak).coerceIn(0f, 1f) * h
                for (i in 1 until bins) {
                    val x = i * bw
                    val y = h - (arr[i] / peak).coerceIn(0f, 1f) * h
                    drawLine(color, Offset(prevX, prevY), Offset(x, y), strokeWidth = 1.2f)
                    prevX = x; prevY = y
                }
            }
        }

        for (q in 1..3) {
            val x = w * q / 4f
            drawLine(Color(0x33FFFFFF), Offset(x, 0f), Offset(x, h), strokeWidth = 0.8f)
        }

        if (data.clippedFraction > 0.001f) {
            drawRect(
                color = Color(0x66FF4438),
                topLeft = Offset(w - bw * 6, 0f),
                size = androidx.compose.ui.geometry.Size(bw * 6, h),
            )
        }
        if (data.crushedFraction > 0.001f) {
            drawRect(
                color = Color(0x663B7BFF),
                topLeft = Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(bw * 6, h),
            )
        }
    }
}

/**
 * Focus reticle at the tapped point. Colour tracks CONTROL_AF_STATE so the
 * frame confirms lock the way an SLR's AF point does, instead of leaving you
 * guessing whether the tap took.
 */
@Composable
fun FocusReticle(
    x: Float,
    y: Float,
    afState: Int?,
    modifier: Modifier = Modifier,
) {
    val color = when (afState) {
        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED,
        -> ProTheme.Ok

        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
        -> ProTheme.Warn

        else -> Color(0xE6FFFFFF)
    }
    Canvas(modifier.fillMaxSize()) {
        val cx = x * size.width
        val cy = y * size.height
        val half = size.minDimension * 0.075f
        val corner = half * 0.42f
        val w = 2.6f

        // four corner brackets
        val pts = listOf(
            Triple(cx - half, cy - half, 1f to 1f),
            Triple(cx + half, cy - half, -1f to 1f),
            Triple(cx - half, cy + half, 1f to -1f),
            Triple(cx + half, cy + half, -1f to -1f),
        )
        for ((px, py, dir) in pts) {
            drawLine(color, Offset(px, py), Offset(px + corner * dir.first, py), strokeWidth = w)
            drawLine(color, Offset(px, py), Offset(px, py + corner * dir.second), strokeWidth = w)
        }
        drawCircle(color, radius = 2.5f, center = Offset(cx, cy))
    }
}

/** Rule-of-thirds grid drawn over the preview. */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val c = Color(0x40FFFFFF)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            drawLine(c, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
    }
}

/** P / S / M / MR selector on the left edge. */
@Composable
fun ModeDial(
    modes: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        modes.forEach { m ->
            Text(
                m,
                color = if (m == selected) ProTheme.Accent else ProTheme.TextSecondary,
                fontSize = if (m == selected) 20.sp else 16.sp,
                fontFamily = ProTheme.Mono,
                fontWeight = if (m == selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clickable { onSelect(m) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Electronic level. A fixed reference bar sits in the middle of the frame and
 * the horizon bar rolls against it; both snap to green the moment the camera is
 * actually square. The pitch ladder on the right shows forward/back tilt.
 */
@Composable
fun LevelOverlay(
    rollDeg: Float,
    pitchDeg: Float,
    isLevel: Boolean,
    modifier: Modifier = Modifier,
) {
    val live = if (isLevel) ProTheme.Ok else Color(0xE6FFFFFF)
    Canvas(modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arm = size.width * 0.17f
        val gap = size.width * 0.045f

        // static reference: short centre marks that never move
        drawLine(
            Color(0x66FFFFFF),
            Offset(cx - gap, cy), Offset(cx + gap, cy),
            strokeWidth = 2f,
        )

        // Rolling horizon: two collinear segments either side of the centre
        // gap, both walked along the same unit direction so they stay on one
        // straight line at any tilt.
        val rad = Math.toRadians(-rollDeg.toDouble())
        val ux = kotlin.math.cos(rad).toFloat()
        val uy = kotlin.math.sin(rad).toFloat()
        val inner = gap * 1.6f
        drawLine(
            live,
            Offset(cx - ux * arm, cy - uy * arm),
            Offset(cx - ux * inner, cy - uy * inner),
            strokeWidth = 3f,
        )
        drawLine(
            live,
            Offset(cx + ux * inner, cy + uy * inner),
            Offset(cx + ux * arm, cy + uy * arm),
            strokeWidth = 3f,
        )

        if (isLevel) {
            drawCircle(ProTheme.Ok, radius = 4f, center = Offset(cx, cy))
        }

        // pitch ladder
        val px = size.width * 0.92f
        val half = size.height * 0.16f
        drawLine(
            Color(0x40FFFFFF),
            Offset(px, cy - half), Offset(px, cy + half),
            strokeWidth = 1.5f,
        )
        drawLine(
            Color(0x66FFFFFF),
            Offset(px - 9f, cy), Offset(px + 9f, cy),
            strokeWidth = 1.5f,
        )
        val clamped = pitchDeg.coerceIn(-45f, 45f)
        val py = cy - (clamped / 45f) * half
        val pitchOk = abs(pitchDeg) <= 1.5f
        drawLine(
            if (pitchOk) ProTheme.Ok else Color(0xE6FFFFFF),
            Offset(px - 13f, py), Offset(px + 13f, py),
            strokeWidth = 3f,
        )
    }
}

/**
 * Shutter release, styled after a DSLR release button: a machined outer ring,
 * a raised inner disc that depresses on touch, and an amber sweep while the
 * sensor is actually integrating — which matters here, because a 32 s exposure
 * needs to visibly still be running.
 */
@Composable
fun ShutterButton(
    busy: Boolean,
    enabled: Boolean,
    rawArmed: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val discScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = tween(90),
        label = "shutterPress",
    )
    val sweep by rememberInfiniteTransition(label = "shutterBusy")
        .animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "shutterSweep",
        )

    Box(
        modifier
            .size(74.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !busy,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val ringStroke = r * 0.075f
            val ringR = r - ringStroke / 2f

            // Outer bezel. It goes amber as a whole when a DNG is armed — a
            // partial arc there read as a rendering glitch rather than a state.
            drawCircle(
                color = when {
                    !enabled -> Color(0xFF2A2A2A)
                    rawArmed -> ProTheme.Accent
                    else -> Color(0xFF6A6A6A)
                },
                radius = ringR,
                style = Stroke(width = ringStroke),
            )

            // busy sweep rides just inside the bezel
            if (busy) {
                val inset = ringStroke * 2.2f
                drawArc(
                    color = ProTheme.Accent,
                    startAngle = sweep,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - inset * 2, size.height - inset * 2,
                    ),
                    style = Stroke(width = ringStroke * 1.3f, cap = StrokeCap.Round),
                )
            }

            // inner release disc
            val discR = r * 0.68f * discScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = when {
                        !enabled -> listOf(Color(0xFF3A3A3A), Color(0xFF262626))
                        busy -> listOf(Color(0xFFFFD79A), ProTheme.Accent)
                        else -> listOf(Color(0xFFFFFFFF), Color(0xFFC8C8C8))
                    },
                    center = Offset(size.width * 0.40f, size.height * 0.36f),
                    radius = discR * 1.7f,
                ),
                radius = discR,
            )
            drawCircle(
                color = Color(0x26000000),
                radius = discR,
                style = Stroke(width = 1.2f),
            )
        }
    }
}
