// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Photo Pro-style palette: everything is black so nothing competes with the
 * image, one warm amber accent marks whatever is currently being adjusted.
 */
object ProTheme {
    val Black = Color(0xFF000000)
    val Panel = Color(0xFF0A0A0A)
    val Chip = Color(0xFF181818)
    val ChipBorder = Color(0xFF2E2E2E)
    val Divider = Color(0xFF262626)

    val TextPrimary = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xFF9A9A9A)
    val TextDim = Color(0xFF5C5C5C)

    val Accent = Color(0xFFFFA028)
    val AccentDim = Color(0x66FFA028)
    val Warn = Color(0xFFFF4438)
    val Ok = Color(0xFF35D07F)

    val Mono = FontFamily.Monospace

    val LabelSize = 9.sp
    val ValueSize = 13.sp
    val ReadoutSize = 15.sp
    val ScrubActiveSize = 19.sp
    val ScrubIdleSize = 13.sp
}
