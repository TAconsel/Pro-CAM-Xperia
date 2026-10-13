// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Mechanical-feeling detents for the adjustment ruler.
 *
 * The platform's generic feedback constants are deliberately soft, which reads
 * as mush when values stream past under a fast drag. A real lens ring answers
 * every graduation and answers whole stops harder, so this drives the actuator
 * directly: a composition primitive where the hardware supports one (crisp and
 * amplitude-controllable), a predefined effect next, and a short amplitude
 * one-shot as the floor.
 */
class HapticEngine(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    private val supportsClickPrimitive: Boolean = runCatching {
        vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true
    }.getOrDefault(false)

    private val supportsTickPrimitive: Boolean = runCatching {
        vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) == true
    }.getOrDefault(false)

    private val hasAmplitudeControl: Boolean =
        runCatching { vibrator?.hasAmplitudeControl() == true }.getOrDefault(false)

    private var lastFiredAt = 0L

    /**
     * One graduation passed.
     *
     * @param major whole-stop graduations get a firmer click, the way a
     *   detented aperture ring distinguishes stops from thirds.
     */
    fun detent(major: Boolean = false) {
        // A fast flick can cross a graduation every few milliseconds. Queuing
        // all of them makes the actuator drone instead of click, so anything
        // closer than one detent-length apart is dropped.
        val now = SystemClock.uptimeMillis()
        if (now - lastFiredAt < MIN_INTERVAL_MS) return
        lastFiredAt = now
        play(if (major) MAJOR_SCALE else MINOR_SCALE, major)
    }

    /** Hit the end of the scale — a duller, heavier thud than a graduation. */
    fun endStop() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFiredAt < END_STOP_INTERVAL_MS) return
        lastFiredAt = now
        play(END_STOP_SCALE, major = true, durationMs = 18)
    }

    /** AF found focus — the job a camera body's focus beep does. */
    fun focusLocked() {
        lastFiredAt = SystemClock.uptimeMillis()
        play(MAJOR_SCALE, major = true, durationMs = 20)
    }

    /** AF gave up: two light ticks, so it cannot be taken for a lock. */
    fun focusFailed() {
        lastFiredAt = SystemClock.uptimeMillis()
        val v = vibrator ?: return
        runCatching {
            when {
                supportsTickPrimitive -> v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 90)
                        .compose(),
                )

                else -> v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 10, 90, 10), -1))
            }
        }
    }

    /** The shot is written: the same short click as a focus lock. */
    fun saved() {
        lastFiredAt = SystemClock.uptimeMillis()
        play(MAJOR_SCALE, major = true, durationMs = 20)
    }

    private fun play(scale: Float, major: Boolean, durationMs: Long = 0) {
        val v = vibrator ?: return
        runCatching {
            when {
                supportsClickPrimitive && major -> v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                        .compose(),
                )

                supportsTickPrimitive -> v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                        .compose(),
                )

                supportsClickPrimitive -> v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                        .compose(),
                )

                hasAmplitudeControl -> v.vibrate(
                    VibrationEffect.createOneShot(
                        if (durationMs > 0) durationMs else if (major) 12L else 8L,
                        (scale * 255).toInt().coerceIn(1, 255),
                    ),
                )

                else -> v.vibrate(
                    VibrationEffect.createPredefined(
                        if (major) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK,
                    ),
                )
            }
        }
    }

    private companion object {
        const val MINOR_SCALE = 0.45f
        const val MAJOR_SCALE = 1.0f
        const val END_STOP_SCALE = 0.75f
        const val MIN_INTERVAL_MS = 16L
        const val END_STOP_INTERVAL_MS = 120L
    }
}
