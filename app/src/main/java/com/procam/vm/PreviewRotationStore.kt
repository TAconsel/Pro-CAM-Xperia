// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.vm

import android.content.Context
import android.os.Build

/**
 * Per-device preview rotation correction.
 *
 * The documented relationship — SENSOR_ORIENTATION minus the display rotation —
 * is what this app computes, and it is what should be right everywhere. It is
 * not, quite: on the Nothing Phone (2a) Plus the viewfinder lands a quarter
 * turn from upright, measured by shooting a JPEG and comparing it against the
 * live preview in the same session.
 *
 * Rather than bake that one device's quirk into the formula for everybody, the
 * correction is stored per `Build.MODEL`. Unknown hardware starts at zero — the
 * textbook answer — and if a device disagrees the user turns it right once with
 * the ROT control and the choice sticks for that model.
 */
class PreviewRotationStore(context: Context) {

    private val prefs = context.getSharedPreferences("procam_rotation", Context.MODE_PRIVATE)

    private val key: String = "rot_" + (Build.MODEL ?: "unknown").replace(Regex("[^A-Za-z0-9]"), "_")

    fun load(): Int = prefs.getInt(key, defaultForThisDevice())

    fun save(degrees: Int) {
        prefs.edit().putInt(key, ((degrees % 360) + 360) % 360).apply()
    }

    /** True once the user has set this model's value by hand. */
    fun isUserSet(): Boolean = prefs.contains(key)

    private fun defaultForThisDevice(): Int = MEASURED[Build.MODEL] ?: 0

    private companion object {
        /**
         * Corrections confirmed against a captured frame on real hardware.
         * Add an entry only when it has actually been checked on that device.
         */
        val MEASURED = mapOf(
            "A142P" to 90, // Nothing Phone (2a) Plus
            "XQ-FS72" to 90, // Sony Xperia 1 VII — every camera, front included
        )
    }
}
