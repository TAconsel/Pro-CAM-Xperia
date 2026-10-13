// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.capture

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import android.media.Image
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.procam.camera.formatShutter

private const val TAG = "ProCam.Media"
private const val ALBUM = "DCIM/ProCam"

data class SavedShot(
    val jpegUri: Uri? = null,
    val dngUri: Uri? = null,
    val dngBytes: Long = 0,
    val jpegBytes: Long = 0,
    val error: String? = null,
)

class MediaWriter(private val context: Context) {

    private val resolver get() = context.contentResolver

    // ------------------------------------------------------------------
    // DNG
    // ------------------------------------------------------------------

    /**
     * Writes a RAW_SENSOR image as DNG. [result] must be the TotalCaptureResult
     * of the very frame [image] came from — DngCreator embeds the colour
     * matrices, black levels and noise profile from it, and a mismatched result
     * produces a technically valid but wrongly-developed file.
     */
    fun writeDng(
        image: Image,
        result: TotalCaptureResult,
        characteristics: CameraCharacteristics,
        orientationDegrees: Int,
        location: Location?,
        baseName: String,
    ): Pair<Uri?, Long> {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null to 0L

        var written = 0L
        var dng: DngCreator? = null
        try {
            dng = DngCreator(characteristics, result)
            dng.setOrientation(exifOrientationOf(orientationDegrees))
            location?.let { runCatching { dng.setLocation(it) } }
            dng.setDescription(describeShot(result))

            resolver.openOutputStream(uri)?.use { os ->
                dng.writeImage(os, image)
            }
            written = sizeOf(uri)
        } catch (t: Throwable) {
            Log.e(TAG, "writeDng failed", t)
            runCatching { resolver.delete(uri, null, null) }
            return null to 0L
        } finally {
            runCatching { dng?.close() }
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.i(TAG, "DNG saved $uri ($written bytes)")
        return uri to written
    }

    // ------------------------------------------------------------------
    // JPEG
    // ------------------------------------------------------------------

    /**
     * The HAL already stamps the basics into the JPEG, but not consistently
     * across vendors, so the capture metadata is re-applied explicitly.
     */
    fun writeJpeg(
        bytes: ByteArray,
        result: TotalCaptureResult?,
        focalLengthMm: Float,
        aperture: Float,
        location: Location?,
        baseName: String,
    ): Pair<Uri?, Long> {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null to 0L

        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
        } catch (t: Throwable) {
            Log.e(TAG, "writeJpeg failed", t)
            runCatching { resolver.delete(uri, null, null) }
            return null to 0L
        }

        // Stamped in place through the MediaStore file descriptor: androidx's
        // ExifInterface can only rewrite a seekable target, and while the entry
        // is still IS_PENDING we own it exclusively.
        runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                stampExif(pfd.fileDescriptor, result, focalLengthMm, aperture, location)
            }
        }.onFailure { Log.w(TAG, "EXIF stamping failed; image kept as-is", it) }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        val size = sizeOf(uri).takeIf { it > 0 } ?: bytes.size.toLong()
        Log.i(TAG, "JPEG saved $uri ($size bytes)")
        return uri to size
    }

    private fun stampExif(
        fd: java.io.FileDescriptor,
        result: TotalCaptureResult?,
        focalLengthMm: Float,
        aperture: Float,
        location: Location?,
    ) {
        val exif = ExifInterface(fd)

        result?.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString())
        }
        result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { ns ->
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (ns / 1_000_000_000.0).toString())
        }
        val focal = result?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: focalLengthMm
        if (focal > 0f) {
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(focal * 1000).toInt()}/1000")
        }
        val f = result?.get(CaptureResult.LENS_APERTURE) ?: aperture
        if (f > 0f) exif.setAttribute(ExifInterface.TAG_F_NUMBER, "${(f * 1000).toInt()}/1000")

        result?.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { d ->
            if (d > 0f) {
                exif.setAttribute(
                    ExifInterface.TAG_SUBJECT_DISTANCE,
                    "${(1f / d * 1000).toInt()}/1000",
                )
            }
        }

        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ProCam")
        exif.setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, "1") // manual
        location?.let { runCatching { exif.setGpsInfo(it) } }

        exif.saveAttributes()
    }

    // ------------------------------------------------------------------

    private fun sizeOf(uri: Uri): Long = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull() ?: 0L

    private fun describeShot(r: TotalCaptureResult): String {
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY)
        val exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val focal = r.get(CaptureResult.LENS_FOCAL_LENGTH)
        val ap = r.get(CaptureResult.LENS_APERTURE)
        return "ProCam ISO$iso ${exp?.let(::formatShutter)} ${focal}mm f/$ap"
    }

    private fun exifOrientationOf(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
