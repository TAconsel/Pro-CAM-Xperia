// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class LevelState(
    /** Rotation of the horizon in the frame. 0 = level. */
    val rollDeg: Float = 0f,
    /** Tilt away from the horizon. 0 = camera pointing at eye level. */
    val pitchDeg: Float = 0f,
    val available: Boolean = false,
) {
    val isLevel: Boolean get() = abs(rollDeg) <= 1.0f
    val isSquare: Boolean get() = isLevel && abs(pitchDeg) <= 1.5f
}

/**
 * Two-axis electronic level driven by the gravity vector.
 *
 * Only gravity is needed, so this deliberately avoids ROTATION_VECTOR and the
 * magnetometer — a compass fix would add drift and warm-up time for a readout
 * that only has to answer "is the horizon straight".
 */
class LevelSensor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _state = MutableStateFlow(LevelState())
    val state: StateFlow<LevelState> = _state

    /** Surface.ROTATION_* of the window the overlay is drawn in. */
    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var primed = false

    fun start() {
        val s = sensor ?: return
        manager.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Light low-pass: the bubble should settle, not jitter with hand shake.
        val a = if (primed) 0.15f else 1f
        gx += a * (event.values[0] - gx)
        gy += a * (event.values[1] - gy)
        gz += a * (event.values[2] - gz)
        primed = true

        // Gravity arrives in the device's natural frame; rotate it into the
        // frame the UI is actually drawn in.
        val (sx, sy) = when (displayRotation) {
            Surface.ROTATION_90 -> -gy to gx
            Surface.ROTATION_180 -> -gx to -gy
            Surface.ROTATION_270 -> gy to -gx
            else -> gx to gy
        }

        val roll = Math.toDegrees(atan2(sx.toDouble(), sy.toDouble())).toFloat()
        val pitch = Math.toDegrees(
            atan2(-gz.toDouble(), sqrt((sx * sx + sy * sy).toDouble())),
        ).toFloat()

        _state.value = LevelState(rollDeg = roll, pitchDeg = pitch, available = true)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
