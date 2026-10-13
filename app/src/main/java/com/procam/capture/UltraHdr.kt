// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val TAG = "ProCam.UltraHdr"

/**
 * Ultra HDR from RAW.
 *
 * An Ultra HDR JPEG is the ordinary SDR picture plus a gain map: per region,
 * how much brighter an HDR screen may show it. The camera HAL can produce one
 * itself (ImageFormat.JPEG_R), but on the Xperia 1 VII it only fills the gain
 * map for Sony's own camera app; for anyone else it is a valid file whose gain
 * map is flat zero, so nothing ever gets brighter.
 *
 * So the gain map is built here. The RAW frame keeps the highlight range that
 * the SDR JPEG's tone curve compresses and clips. Scaled to match the JPEG in
 * the midtones, RAW luminance over JPEG luminance is the boost each region
 * needs to look on an HDR screen the way the scene did. The SDR JPEG itself is
 * kept byte for byte; the gain map and the standard Ultra HDR metadata (XMP
 * hdrgm + container directory, and an MPF index) are wrapped around it.
 */
object UltraHdr {

    /** Gain map pixels per JPEG pixel along each side. */
    private const val GAIN_MAP_SCALE = 4

    /** Strongest boost written, in stops: 3 stops is 8x SDR white. */
    private const val MAX_STOPS = 3.0

    /** Below this the scene has no highlights SDR was holding back. */
    private const val MIN_USEFUL_RATIO = 1.1

    /** The bracket frame must be at least this much darker to be used. */
    private const val MIN_BRACKET_RATIO = 1.5f

    /** Normal-frame photosite level where the dark frame starts to take over. */
    private const val BLEND_START = 0.7f
    private const val BLEND_FULL = 0.95f

    /** Linear SDR luminance where the boost starts, and where it is full. */
    private const val HIGHLIGHT_START = 0.18f
    private const val HIGHLIGHT_FULL = 0.6f

    /** hdrgm offsets: keep blacks from turning the ratio into noise. */
    private const val OFFSET = 1.0 / 64.0

    class Result(val jpeg: ByteArray, val maxBoostStops: Double)

    /**
     * @param jpegOrientation what the still request asked for in
     *   JPEG_ORIENTATION — needed when the HAL rotated the pixels rather than
     *   writing an EXIF orientation.
     * @return the Ultra HDR file, or null when the scene has no highlight
     *   headroom worth a gain map (or the RAW cannot be read).
     */
    fun encode(
        sdrJpeg: ByteArray,
        raw: Image,
        result: TotalCaptureResult,
        characteristics: CameraCharacteristics,
        jpegOrientation: Int,
        darkRaw: Image? = null,
        darkResult: TotalCaptureResult? = null,
    ): Result? = runCatching {
        val sdr = decodeSdrLuminance(sdrJpeg) ?: return null
        var rawMap = rawLuminance(raw, result, characteristics) ?: return null
        if (darkRaw != null && darkResult != null) {
            rawLuminance(darkRaw, darkResult, characteristics)?.let { dark ->
                rawMap = mergeBracket(rawMap, result, dark, darkResult)
            }
        }
        val aligned = alignToJpeg(rawMap, sdr, sdrJpeg, jpegOrientation)
        val gain = gainMap(aligned, sdr) ?: return null
        Result(assemble(sdrJpeg, gain), ln(gain.ratioMax) / ln(2.0))
    }.onFailure { Log.e(TAG, "Ultra HDR encoding failed", it) }.getOrNull()

    // ------------------------------------------------------------------
    // luminance maps
    // ------------------------------------------------------------------

    /**
     * Linear luminance on a W x H grid. For RAW maps [clipped] marks blocks
     * where a photosite hit white, and [peak] is the brightest photosite of
     * the block as a fraction of white.
     */
    private class LumMap(
        val w: Int,
        val h: Int,
        val y: FloatArray,
        val clipped: BooleanArray? = null,
        val peak: FloatArray? = null,
    )

    private val SRGB_TO_LINEAR = FloatArray(256) { i ->
        val c = i / 255.0
        (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    private fun decodeSdrLuminance(jpeg: ByteArray): LumMap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = GAIN_MAP_SCALE
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val y = FloatArray(w * h) { i ->
            val p = px[i]
            0.2126f * SRGB_TO_LINEAR[(p shr 16) and 0xff] +
                0.7152f * SRGB_TO_LINEAR[(p shr 8) and 0xff] +
                0.0722f * SRGB_TO_LINEAR[p and 0xff]
        }
        return LumMap(w, h, y)
    }

    /**
     * Bins the Bayer frame into 4x4 blocks — two quads each way, so every
     * block holds 4 red, 8 green and 4 blue photosites — and gives each a
     * white-balanced linear luminance. No colour matrix: only the ratio to
     * the JPEG matters, and that is calibrated afterwards.
     */
    private fun rawLuminance(raw: Image, result: TotalCaptureResult, c: CameraCharacteristics): LumMap? {
        val plane = raw.planes.firstOrNull() ?: return null
        val rowShorts = plane.rowStride / 2
        val buf: ByteBuffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val shorts = buf.asShortBuffer()
        val rw = raw.width
        val rh = raw.height
        val w = rw / GAIN_MAP_SCALE
        val h = rh / GAIN_MAP_SCALE

        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                FloatArray(4) { i -> p.getOffsetForIndex(i % 2, i / 2).toFloat() }
            }
            ?: FloatArray(4) { 64f }
        val white = (result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: 1023).toFloat()
        val wb = result.get(CaptureResult.COLOR_CORRECTION_GAINS)

        // Channel of each position in a 2x2 quad: 0 = R, 1 = G, 2 = B.
        val cfa = when (c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0, 2, 1)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(1, 2, 0, 1)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(2, 1, 1, 0)
            else -> intArrayOf(0, 1, 1, 2) // RGGB
        }
        val gains = floatArrayOf(wb?.red ?: 2f, wb?.greenEven ?: 1f, wb?.blue ?: 2f)
        val range = FloatArray(4) { i -> max(1f, white - black[i]) }

        val y = FloatArray(w * h)
        val clipped = BooleanArray(w * h)
        val peak = FloatArray(w * h)
        val row = ShortArray(rw)
        val sum = FloatArray(3)
        val acc = FloatArray(w * 3)
        val clipRow = BooleanArray(w)
        val peakRow = FloatArray(w)
        for (by in 0 until h) {
            acc.fill(0f)
            clipRow.fill(false)
            peakRow.fill(0f)
            for (dy in 0 until GAIN_MAP_SCALE) {
                val sy = by * GAIN_MAP_SCALE + dy
                shorts.position(sy * rowShorts)
                shorts.get(row, 0, rw)
                val qy = (sy and 1) * 2
                for (x in 0 until w * GAIN_MAP_SCALE) {
                    val q = qy + (x and 1)
                    val raw16 = row[x].toInt() and 0xffff
                    val v = (raw16 - black[q]) / range[q]
                    val bx = x / GAIN_MAP_SCALE
                    acc[bx * 3 + cfa[q]] += v
                    if (v > peakRow[bx]) peakRow[bx] = v
                    if (raw16 >= white * 0.98f) clipRow[bx] = true
                }
            }
            for (bx in 0 until w) {
                // 4 R, 8 G, 4 B samples per block
                sum[0] = acc[bx * 3] / 4f * gains[0]
                sum[1] = acc[bx * 3 + 1] / 8f * gains[1]
                sum[2] = acc[bx * 3 + 2] / 4f * gains[2]
                y[by * w + bx] = max(0f, 0.2126f * sum[0] + 0.7152f * sum[1] + 0.0722f * sum[2])
                clipped[by * w + bx] = clipRow[bx]
                peak[by * w + bx] = peakRow[bx]
            }
        }
        return LumMap(w, h, y, clipped, peak)
    }

    /**
     * Where the normal frame runs into white, the darker frame of the bracket
     * still has the highlight; scaled up by the exposure difference it says
     * how bright that region really was. Everywhere else the normal frame,
     * with its better shadows, is kept.
     *
     * The scale is measured, not taken from the metadata: blocks that are
     * well exposed in both frames give the true brightness ratio. If the dark
     * frame turns out not to be darker — a HAL that served a buffered frame
     * instead — it is ignored rather than allowed to multiply every bright
     * region into a hard-edged blob.
     */
    private fun mergeBracket(
        main: LumMap,
        mainResult: TotalCaptureResult,
        dark: LumMap,
        darkResult: TotalCaptureResult,
    ): LumMap {
        if (dark.w != main.w || dark.h != main.h) return main
        val mainPeak = main.peak ?: return main
        val darkPeak = dark.peak ?: return main

        val ratios = ArrayList<Float>(main.y.size / 4)
        for (i in main.y.indices) {
            if (mainPeak[i] < 0.8f && darkPeak[i] < 0.8f && main.y[i] > 0.05f && dark.y[i] > 1e-3f) {
                ratios += main.y[i] / dark.y[i]
            }
        }
        if (ratios.size < 500) return main
        ratios.sort()
        val scale = ratios[ratios.size / 2]
        val metadata = run {
            fun e(r: TotalCaptureResult) = (r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L).toDouble() *
                (r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0).toDouble()
            if (e(darkResult) > 0) e(mainResult) / e(darkResult) else 0.0
        }
        Log.i(
            TAG,
            "bracket: dark frame measured ${"%.2f".format(java.util.Locale.ROOT, ln(scale.toDouble()) / ln(2.0))} " +
                "stops under (metadata ${"%.2f".format(java.util.Locale.ROOT, ln(metadata) / ln(2.0))})",
        )
        if (scale < MIN_BRACKET_RATIO) {
            Log.w(TAG, "bracket frame is not darker — highlights from the normal frame only")
            return main
        }

        val y = FloatArray(main.y.size)
        val clipped = BooleanArray(main.y.size)
        for (i in y.indices) {
            // Blend toward the dark frame as the normal one approaches white,
            // so there is no edge where one frame hands over to the other.
            val w = smoothstep(BLEND_START, BLEND_FULL, mainPeak[i])
            val fromDark = dark.y[i] * scale
            y[i] = main.y[i] + (max(main.y[i], fromDark) - main.y[i]) * w
            clipped[i] = darkPeak[i] >= 0.98f
        }
        return LumMap(main.w, main.h, y, clipped, null)
    }

    /**
     * Brings the RAW map onto the JPEG's grid: the same rotation the HAL gave
     * the JPEG pixels, the centre crop of a non-4:3 RES choice, then a
     * resample to the decoded size.
     */
    private fun alignToJpeg(rawMap: LumMap, sdr: LumMap, jpeg: ByteArray, jpegOrientation: Int): LumMap {
        val exifOrientation = runCatching {
            ExifInterface(jpeg.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        // An EXIF rotation tag means the pixels are still in sensor order.
        val rotation = if (exifOrientation == ExifInterface.ORIENTATION_NORMAL ||
            exifOrientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            ((jpegOrientation % 360) + 360) % 360
        } else {
            0
        }
        var src = rawMap
        if (rotation != 0) src = rotate(src, rotation)

        // centre crop to the JPEG's aspect ratio
        val target = sdr.w.toFloat() / sdr.h
        val cur = src.w.toFloat() / src.h
        var cx = 0f
        var cy = 0f
        var cw = src.w.toFloat()
        var ch = src.h.toFloat()
        if (target > cur + 0.01f) {
            ch = src.w / target; cy = (src.h - ch) / 2f
        } else if (target < cur - 0.01f) {
            cw = src.h * target; cx = (src.w - cw) / 2f
        }

        val y = FloatArray(sdr.w * sdr.h)
        val clip = BooleanArray(sdr.w * sdr.h)
        for (ty in 0 until sdr.h) {
            val sy = min(src.h - 1, (cy + (ty + 0.5f) * ch / sdr.h).toInt())
            for (tx in 0 until sdr.w) {
                val sx = min(src.w - 1, (cx + (tx + 0.5f) * cw / sdr.w).toInt())
                y[ty * sdr.w + tx] = src.y[sy * src.w + sx]
                clip[ty * sdr.w + tx] = src.clipped?.get(sy * src.w + sx) ?: false
            }
        }
        return LumMap(sdr.w, sdr.h, y, clip)
    }

    /** Clockwise rotation by 90, 180 or 270 degrees. */
    private fun rotate(m: LumMap, degrees: Int): LumMap {
        val ow = if (degrees == 180) m.w else m.h
        val oh = if (degrees == 180) m.h else m.w
        val y = FloatArray(ow * oh)
        val clip = BooleanArray(ow * oh)
        for (sy in 0 until m.h) for (sx in 0 until m.w) {
            val (dx, dy) = when (degrees) {
                90 -> (m.h - 1 - sy) to sx
                180 -> (m.w - 1 - sx) to (m.h - 1 - sy)
                else -> sy to (m.w - 1 - sx) // 270
            }
            y[dy * ow + dx] = m.y[sy * m.w + sx]
            clip[dy * ow + dx] = m.clipped?.get(sy * m.w + sx) ?: false
        }
        return LumMap(ow, oh, y, clip)
    }

    // ------------------------------------------------------------------
    // gain map
    // ------------------------------------------------------------------

    private class GainMap(val w: Int, val h: Int, val encoded: ByteArray, val ratioMax: Double)

    private fun gainMap(raw: LumMap, sdr: LumMap): GainMap? {
        val n = sdr.w * sdr.h
        val clipped = raw.clipped ?: BooleanArray(n)

        // Exposure match: in the midtones the JPEG's tone curve is close to
        // linear, so the median ratio there is the RAW-to-JPEG scale.
        val ratios = ArrayList<Float>(n / 4)
        for (i in 0 until n) {
            val s = sdr.y[i]
            val r = raw.y[i]
            if (!clipped[i] && s in 0.04f..0.5f && r > 1e-4f) ratios += s / r
        }
        if (ratios.size < 200) {
            Log.w(TAG, "too few midtone samples (${ratios.size}) to match RAW to JPEG")
            return null
        }
        ratios.sort()
        val k = ratios[ratios.size / 2]

        val gain = FloatArray(n)
        for (i in 0 until n) {
            var hdr = raw.y[i] * k
            // A clipped RAW region was at least as bright as the JPEG says.
            if (clipped[i]) hdr = max(hdr, sdr.y[i])
            val ratio = max(1f, ((hdr + OFFSET) / (sdr.y[i] + OFFSET)).toFloat())
            // Only the highlights get the extra range. Below them the JPEG's
            // tone curve is a look, not a loss — its deeper shadows would
            // otherwise read as "RAW was brighter" and get lifted on an HDR
            // screen. Fade in from midtones to the shoulder.
            val w = smoothstep(HIGHLIGHT_START, HIGHLIGHT_FULL, sdr.y[i])
            gain[i] = 1f + (ratio - 1f) * w
        }
        blur(gain, sdr.w, sdr.h)
        blur(gain, sdr.w, sdr.h)

        val sorted = gain.copyOf().also { it.sort() }
        val p999 = sorted[(sorted.size * 0.999).toInt().coerceAtMost(sorted.size - 1)].toDouble()
        val ratioMax = p999.coerceAtMost(2.0.pow(MAX_STOPS))
        Log.i(TAG, "RAW->JPEG scale=$k gain p50=${sorted[sorted.size / 2]} p99.9=$p999 ratioMax=$ratioMax")
        if (ratioMax < MIN_USEFUL_RATIO) return null

        val logMax = ln(ratioMax)
        val px = IntArray(n) { i ->
            val v = (ln(gain[i].toDouble()) / logMax).coerceIn(0.0, 1.0)
            val g = (v * 255.0 + 0.5).toInt()
            (0xff shl 24) or (g shl 16) or (g shl 8) or g
        }
        val bmp = Bitmap.createBitmap(px, sdr.w, sdr.h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return GainMap(sdr.w, sdr.h, out.toByteArray(), ratioMax)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 3x3 box blur, in place: hides the RAW/JPEG misalignment at edges. */
    private fun blur(a: FloatArray, w: Int, h: Int) {
        val t = a.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) { s += t[yy * w + xx]; c++ }
            }
            a[y * w + x] = s / c
        }
    }

    // ------------------------------------------------------------------
    // container
    // ------------------------------------------------------------------

    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val MPF_ID = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun gainMapXmp(stops: Double) = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="ProCam">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMin="0" hdrgm:GainMapMax="${"%.6f".format(java.util.Locale.ROOT, stops)}" hdrgm:Gamma="1" hdrgm:OffsetSDR="${"%.6f".format(java.util.Locale.ROOT, OFFSET)}" hdrgm:OffsetHDR="${"%.6f".format(java.util.Locale.ROOT, OFFSET)}" hdrgm:HDRCapacityMin="0" hdrgm:HDRCapacityMax="${"%.6f".format(java.util.Locale.ROOT, stops)}" hdrgm:BaseRenditionIsHDR="False"/>
</rdf:RDF>
</x:xmpmeta>"""

    private fun primaryXmp(gainMapLength: Int) = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="ProCam">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0">
<Container:Directory><rdf:Seq>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="$gainMapLength"/></rdf:li>
</rdf:Seq></Container:Directory>
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>"""

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /** Inserts [segments] after SOI and any leading Exif APP1; drops old XMP / MPF. */
    private fun withSegments(jpeg: ByteArray, segments: List<ByteArray>): ByteArray {
        require(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        val out = ByteArrayOutputStream(jpeg.size + 4096)
        out.write(jpeg, 0, 2)
        var p = 2
        var inserted = false
        while (p + 4 <= jpeg.size && jpeg[p] == 0xFF.toByte()) {
            val marker = jpeg[p + 1].toInt() and 0xff
            if (marker == 0xDA) break // start of scan: the rest is image data
            val len = ((jpeg[p + 2].toInt() and 0xff) shl 8) or (jpeg[p + 3].toInt() and 0xff)
            val body = p + 4
            fun startsWith(id: ByteArray) = jpeg.size >= body + id.size &&
                (id.indices).all { jpeg[body + it] == id[it] }
            val isXmp = marker == 0xE1 && startsWith(XMP_ID)
            val isMpf = marker == 0xE2 && startsWith(MPF_ID)
            val isExif = marker == 0xE1 && !isXmp
            if (!inserted && !isExif) {
                segments.forEach { out.write(it) }
                inserted = true
            }
            if (!isXmp && !isMpf) out.write(jpeg, p, len + 2)
            p += len + 2
        }
        if (!inserted) segments.forEach { out.write(it) }
        out.write(jpeg, p, jpeg.size - p)
        return out.toByteArray()
    }

    /**
     * MPF index for two images, big-endian TIFF. The second image's offset is
     * measured from the TIFF header inside this segment, so it stays right
     * when an Exif segment before it later grows or shrinks.
     */
    private fun mpfSegment(primarySize: Int, secondarySize: Int, secondaryOffset: Int): ByteArray {
        val b = ByteBuffer.allocate(4 + 8 + 2 + 3 * 12 + 4 + 32).order(ByteOrder.BIG_ENDIAN)
        b.put(MPF_ID)
        b.put('M'.code.toByte()); b.put('M'.code.toByte()); b.putShort(42); b.putInt(8)
        b.putShort(3)
        b.putShort(0xB000.toShort()); b.putShort(7); b.putInt(4); b.put("0100".toByteArray(Charsets.ISO_8859_1))
        b.putShort(0xB001.toShort()); b.putShort(4); b.putInt(1); b.putInt(2)
        b.putShort(0xB002.toShort()); b.putShort(7); b.putInt(32); b.putInt(8 + 2 + 3 * 12 + 4)
        b.putInt(0) // no next IFD
        b.putInt(0x030000); b.putInt(primarySize); b.putInt(0); b.putShort(0); b.putShort(0)
        b.putInt(0); b.putInt(secondarySize); b.putInt(secondaryOffset); b.putShort(0); b.putShort(0)
        return segment(0xE2, b.array())
    }

    private fun assemble(sdrJpeg: ByteArray, gain: GainMap): ByteArray {
        val stops = ln(gain.ratioMax) / ln(2.0)
        val secondary = withSegments(gain.encoded, listOf(segment(0xE1, XMP_ID + gainMapXmp(stops).toByteArray())))
        val xmp = segment(0xE1, XMP_ID + primaryXmp(secondary.size).toByteArray())

        // First pass with a placeholder MPF to learn where its TIFF header and
        // the end of the primary land; the MPF segment's size never changes.
        val placeholder = mpfSegment(0, 0, 0)
        val draft = withSegments(sdrJpeg, listOf(xmp, placeholder))
        val tiffHeaderAt = indexOf(draft, placeholder) + 4 + MPF_ID.size
        val primary = withSegments(
            sdrJpeg,
            listOf(xmp, mpfSegment(draft.size, secondary.size, draft.size - tiffHeaderAt)),
        )
        return primary + secondary
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        error("segment not found")
    }
}
