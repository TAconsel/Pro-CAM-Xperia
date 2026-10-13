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
import com.procam.camera.ControlMode
import com.procam.camera.FlashMode
import com.procam.camera.ManualControlState
import com.procam.camera.PeakColor

/**
 * Persistence for the MR slot — the DSLR "custom mode" idea: park a whole
 * shooting setup and get it back with one press, across app restarts.
 *
 * Written field by field rather than as serialised JSON so that adding a
 * control later cannot make an old slot fail to load; missing keys just fall
 * back to the default state.
 */
class MemoryRecallStore(context: Context) {

    private val prefs = context.getSharedPreferences("procam_mr", Context.MODE_PRIVATE)

    fun save(mode: ShootMode, s: ManualControlState) {
        prefs.edit().apply {
            putBoolean(KEY_PRESENT, true)
            putString("mode", mode.name)
            putString("exposureMode", s.exposureMode.name)
            putInt("iso", s.iso)
            putLong("exposureTimeNs", s.exposureTimeNs)
            putString("focusMode", s.focusMode.name)
            putFloat("focusDiopter", s.focusDiopter)
            putString("wbMode", s.wbMode.name)
            putInt("wbKelvin", s.wbKelvin)
            putFloat("wbTint", s.wbTint)
            putInt("evSteps", s.evSteps)
            putString("flashMode", s.flashMode.name)
            putBoolean("histogramEnabled", s.histogramEnabled)
            putBoolean("peakingEnabled", s.peakingEnabled)
            putFloat("peakingThreshold", s.peakingThreshold)
            putString("peakingColor", s.peakingColor.name)
            putBoolean("zebraEnabled", s.zebraEnabled)
            putFloat("zebraThreshold", s.zebraThreshold)
            putBoolean("gridEnabled", s.gridEnabled)
            putBoolean("levelEnabled", s.levelEnabled)
            putBoolean("rawEnabled", s.rawEnabled)
            putBoolean("fastViewfinder", s.fastViewfinder)
            putBoolean("afSingle", s.afSingle)
            putString("photoSizeKey", s.photoSizeKey)
            putBoolean("hdr", s.hdr)
            putBoolean("gpsEnabled", s.gpsEnabled)
        }.apply()
    }

    fun hasSlot(): Boolean = prefs.getBoolean(KEY_PRESENT, false)

    fun load(): Pair<ShootMode, ManualControlState>? {
        if (!hasSlot()) return null
        val d = ManualControlState()
        val state = d.copy(
            exposureMode = enumOr(prefs.getString("exposureMode", null), d.exposureMode),
            iso = prefs.getInt("iso", d.iso),
            exposureTimeNs = prefs.getLong("exposureTimeNs", d.exposureTimeNs),
            focusMode = enumOr(prefs.getString("focusMode", null), d.focusMode),
            focusDiopter = prefs.getFloat("focusDiopter", d.focusDiopter),
            wbMode = enumOr(prefs.getString("wbMode", null), d.wbMode),
            wbKelvin = prefs.getInt("wbKelvin", d.wbKelvin),
            wbTint = prefs.getFloat("wbTint", d.wbTint),
            evSteps = prefs.getInt("evSteps", d.evSteps),
            flashMode = flashOr(prefs.getString("flashMode", null), d.flashMode),
            histogramEnabled = prefs.getBoolean("histogramEnabled", d.histogramEnabled),
            peakingEnabled = prefs.getBoolean("peakingEnabled", d.peakingEnabled),
            peakingThreshold = prefs.getFloat("peakingThreshold", d.peakingThreshold),
            peakingColor = peakOr(prefs.getString("peakingColor", null), d.peakingColor),
            zebraEnabled = prefs.getBoolean("zebraEnabled", d.zebraEnabled),
            zebraThreshold = prefs.getFloat("zebraThreshold", d.zebraThreshold),
            gridEnabled = prefs.getBoolean("gridEnabled", d.gridEnabled),
            levelEnabled = prefs.getBoolean("levelEnabled", d.levelEnabled),
            rawEnabled = prefs.getBoolean("rawEnabled", d.rawEnabled),
            fastViewfinder = prefs.getBoolean("fastViewfinder", d.fastViewfinder),
            afSingle = prefs.getBoolean("afSingle", d.afSingle),
            photoSizeKey = prefs.getString("photoSizeKey", d.photoSizeKey) ?: d.photoSizeKey,
            hdr = prefs.getBoolean("hdr", d.hdr),
            gpsEnabled = prefs.getBoolean("gpsEnabled", d.gpsEnabled),
        )
        val mode = runCatching {
            ShootMode.valueOf(prefs.getString("mode", ShootMode.M.name)!!)
        }.getOrDefault(ShootMode.M)
        return mode to state
    }

    private fun enumOr(name: String?, fallback: ControlMode) =
        runCatching { ControlMode.valueOf(name!!) }.getOrDefault(fallback)

    private fun flashOr(name: String?, fallback: FlashMode) =
        runCatching { FlashMode.valueOf(name!!) }.getOrDefault(fallback)

    private fun peakOr(name: String?, fallback: PeakColor) =
        runCatching { PeakColor.valueOf(name!!) }.getOrDefault(fallback)

    private companion object {
        const val KEY_PRESENT = "present"
    }
}
