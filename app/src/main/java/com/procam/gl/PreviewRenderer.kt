// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.util.Size
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "ProCam.GL"

/** Everything the overlay shader needs, updated from the UI thread. */
data class OverlaySettings(
    val peakingEnabled: Boolean = false,
    /** Sobel gradient magnitude above which a pixel counts as "in focus". */
    val peakingThreshold: Float = 0.16f,
    val peakingR: Float = 0.10f,
    val peakingG: Float = 1.00f,
    val peakingB: Float = 0.30f,
    val zebraEnabled: Boolean = false,
    /** Luma at or above which the zebra hatching is drawn. */
    val zebraThreshold: Float = 0.94f,
)

private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
uniform mat4 uRotMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vec4 rotated = uRotMatrix * vec4(aTexCoord, 0.0, 1.0);
    vTexCoord = (uTexMatrix * vec4(rotated.xy, 0.0, 1.0)).xy;
}
"""

private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES sTexture;
uniform vec2  uDx;              // one texel along image x, in final tex space
uniform vec2  uDy;              // one texel along image y, in final tex space
uniform float uPeakThreshold;   // negative disables focus peaking
uniform vec3  uPeakColor;
uniform float uZebraThreshold;  // negative disables zebra
uniform float uZebraPhase;

varying vec2 vTexCoord;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float lumaAt(vec2 uv) {
    return dot(texture2D(sTexture, uv).rgb, LUMA);
}

void main() {
    vec4 base = texture2D(sTexture, vTexCoord);
    vec3 col = base.rgb;

    // ---- zebra: clipped highlights get diagonal hatching -----------------
    if (uZebraThreshold >= 0.0) {
        float l = dot(base.rgb, LUMA);
        if (l >= uZebraThreshold) {
            float s = fract((gl_FragCoord.x - gl_FragCoord.y + uZebraPhase) / 14.0);
            col = (s < 0.5) ? vec3(0.0, 0.0, 0.0) : vec3(1.0, 1.0, 1.0);
        }
    }

    // ---- focus peaking: Sobel on luma ------------------------------------
    if (uPeakThreshold >= 0.0) {
        float tl = lumaAt(vTexCoord - uDx - uDy);
        float tc = lumaAt(vTexCoord        - uDy);
        float tr = lumaAt(vTexCoord + uDx - uDy);
        float ml = lumaAt(vTexCoord - uDx);
        float mr = lumaAt(vTexCoord + uDx);
        float bl = lumaAt(vTexCoord - uDx + uDy);
        float bc = lumaAt(vTexCoord        + uDy);
        float br = lumaAt(vTexCoord + uDx + uDy);

        float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
        float gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
        float g  = sqrt(gx * gx + gy * gy);

        if (g > uPeakThreshold) {
            float strength = clamp((g - uPeakThreshold) * 4.0 + 0.55, 0.0, 1.0);
            col = mix(col, uPeakColor, strength);
        }
    }

    gl_FragColor = vec4(col, 1.0);
}
"""

/**
 * Renders the camera's external OES texture to the screen, applying focus
 * peaking and zebra in a single fragment pass.
 *
 * Doing this on the GPU rather than on an ImageAnalysis stream matters: a Sobel
 * over a full preview frame on the CPU would cost tens of milliseconds and lag
 * the very thing it is meant to help you judge.
 */
class PreviewRenderer(
    bufferSize: Size,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val requestRender: () -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile
    var overlay: OverlaySettings = OverlaySettings()

    /** Clockwise degrees needed to bring the sensor image upright on screen. */
    @Volatile
    var rotationDegrees: Int = 0

    @Volatile
    var mirror: Boolean = false

    /**
     * Camera output size. The view outlives a camera switch, so this follows
     * the current camera — otherwise a SurfaceTexture rebuilt after a trip to
     * the background would come back at the first camera's size. The view
     * model resizes the live texture itself before restarting the session.
     */
    @Volatile
    var bufferSize: Size = bufferSize
        set(value) {
            if (value == field) return
            field = value
            surfaceTexture?.setDefaultBufferSize(value.width, value.height)
        }

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uRotMatrix = 0
    private var uDx = 0
    private var uDy = 0
    private var uPeakThreshold = 0
    private var uPeakColor = 0
    private var uZebraThreshold = 0
    private var uZebraPhase = 0

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    private val stMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)
    private val combined = FloatArray(16)
    private val offsetIn = FloatArray(4)
    private val offsetOut = FloatArray(4)

    private var frameCount = 0

    private val quad: FloatBuffer = floatBuffer(
        floatArrayOf(
            // x,    y,   u,   v
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        ),
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // onSurfaceCreated runs again whenever the EGL context is rebuilt, e.g.
        // after the app has been in the background. Anything from the previous
        // context is already invalid, but the handles would otherwise be
        // overwritten and the SurfaceTexture leaked.
        releaseSurfaceTexture()
        if (program != 0) {
            runCatching { GLES20.glDeleteProgram(program) }
            program = 0
        }
        if (textureId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            textureId = 0
        }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uRotMatrix = GLES20.glGetUniformLocation(program, "uRotMatrix")
        uDx = GLES20.glGetUniformLocation(program, "uDx")
        uDy = GLES20.glGetUniformLocation(program, "uDy")
        uPeakThreshold = GLES20.glGetUniformLocation(program, "uPeakThreshold")
        uPeakColor = GLES20.glGetUniformLocation(program, "uPeakColor")
        uZebraThreshold = GLES20.glGetUniformLocation(program, "uZebraThreshold")
        uZebraPhase = GLES20.glGetUniformLocation(program, "uZebraPhase")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )

        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(bufferSize.width, bufferSize.height)
        st.setOnFrameAvailableListener { requestRender() }
        surfaceTexture = st
        onSurfaceTextureReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        } catch (t: Throwable) {
            return
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        buildRotationMatrix()

        // Texel offsets have to travel through the same transforms as the
        // coordinates themselves, otherwise the Sobel kernel samples along the
        // wrong axes whenever the preview is rotated or mirrored.
        Matrix.multiplyMM(combined, 0, stMatrix, 0, rotMatrix, 0)
        val texelW = 1f / bufferSize.width
        val texelH = 1f / bufferSize.height
        val dx = transformOffset(combined, texelW, 0f)
        val dy = transformOffset(combined, 0f, texelH)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uRotMatrix, 1, false, rotMatrix, 0)
        GLES20.glUniform2f(uDx, dx[0], dx[1])
        GLES20.glUniform2f(uDy, dy[0], dy[1])

        val o = overlay
        GLES20.glUniform1f(uPeakThreshold, if (o.peakingEnabled) o.peakingThreshold else -1f)
        GLES20.glUniform3f(uPeakColor, o.peakingR, o.peakingG, o.peakingB)
        GLES20.glUniform1f(uZebraThreshold, if (o.zebraEnabled) o.zebraThreshold else -1f)
        GLES20.glUniform1f(uZebraPhase, (frameCount % 56).toFloat())
        frameCount++

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }


    fun releaseSurfaceTexture() {
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
    }

    private fun buildRotationMatrix() {
        Matrix.setIdentityM(rotMatrix, 0)
        Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rotMatrix, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)
        if (mirror) Matrix.scaleM(rotMatrix, 0, -1f, 1f, 1f)
        Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f)
    }

    private fun transformOffset(m: FloatArray, x: Float, y: Float): FloatArray {
        offsetIn[0] = x; offsetIn[1] = y; offsetIn[2] = 0f; offsetIn[3] = 0f
        Matrix.multiplyMV(offsetOut, 0, m, 0, offsetIn, 0)
        return offsetOut
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            error("program link failed: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            Log.e(TAG, "shader compile failed: $log")
            error("shader compile failed: $log")
        }
        return s
    }
}

/** GLSurfaceView pre-wired for [PreviewRenderer]. */
class CameraGLSurfaceView(context: Context) : GLSurfaceView(context) {
    lateinit var previewRenderer: PreviewRenderer
        private set

    fun init(bufferSize: Size, onSurfaceTextureReady: (SurfaceTexture) -> Unit) {
        setEGLContextClientVersion(2)
        // Where the GPU allows it this keeps the context across a pause, so
        // returning to the app does not have to rebuild shaders and textures.
        preserveEGLContextOnPause = true
        previewRenderer = PreviewRenderer(
            bufferSize = bufferSize,
            onSurfaceTextureReady = onSurfaceTextureReady,
            requestRender = { requestRender() },
        )
        setRenderer(previewRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}
