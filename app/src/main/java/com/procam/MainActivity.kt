// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Khanatuch Phutthikondurong
//
// This file is part of ProCam. ProCam is free software: you can redistribute
// it and/or modify it under the terms of the GNU General Public License as
// published by the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see the LICENSE file for details.

package com.procam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.procam.diag.Diagnostics
import com.procam.ui.CameraScreen
import com.procam.ui.ProTheme
import com.procam.ui.Text2
import com.procam.vm.CameraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var orientationListener: OrientationEventListener? = null

    /** The same instance Compose's viewModel() hands the screens. */
    private val cameraVm: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Let the preview run under the punch-hole; the safeDrawing padding
        // below keeps the actual controls clear of it.
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        hideSystemBars()
        requestFastestDisplayMode()

        // MainActivity is exported (it is the launcher), so any installed app
        // could fire this extra and make the camera open and write files on its
        // own. Honour it only in debuggable builds, where it exists for adb.
        val debuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val startInDiagnostics =
            debuggable && (intent?.getBooleanExtra(EXTRA_RUN_DIAGNOSTICS, false) ?: false)

        setContent {
            val vm: CameraViewModel = viewModel()
            var screen by remember {
                mutableStateOf(if (startInDiagnostics) Screen.DIAGNOSTICS else Screen.CAMERA)
            }
            var granted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
            }

            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result ->
                granted = result[Manifest.permission.CAMERA] == true
            }

            // Camera only. Location is requested from the GPS toggle, at the
            // moment it is about to be written into a file.
            LaunchedEffect(Unit) {
                if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA))
            }

            val locationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result ->
                if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                    vm.toggleGps { }
                }
            }

            // Physical orientation drives JPEG/DNG rotation regardless of the
            // locked landscape UI, so a portrait-held shot still lands upright.
            LaunchedEffect(Unit) {
                orientationListener = object : OrientationEventListener(this@MainActivity) {
                    override fun onOrientationChanged(orientation: Int) {
                        vm.deviceOrientation = orientation
                    }
                }.also { if (it.canDetectOrientation()) it.enable() }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(ProTheme.Black)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                when {
                    !granted -> PermissionPane { launcher.launch(arrayOf(Manifest.permission.CAMERA)) }

                    screen == Screen.DIAGNOSTICS -> DiagnosticsScreen(
                        onBack = { screen = Screen.CAMERA },
                    )

                    else -> {
                        LaunchedEffect(granted) { vm.openCamera() }
                        CameraScreen(
                            vm = vm,
                            onRequestLocationPermission = {
                                locationLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            },
                            onOpenDiagnostics = {
                                vm.closeCamera()
                                screen = Screen.DIAGNOSTICS
                            },
                        )
                    }
                }
            }
        }
    }

    // The two-stage shutter key. Both halves are consumed whatever the screen,
    // so pressing it inside the app never hands control to the system camera.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_FOCUS -> {
                if (event.repeatCount == 0) cameraVm.onShutterHalfPress()
                return true
            }
            KeyEvent.KEYCODE_CAMERA -> {
                if (event.repeatCount == 0) cameraVm.onShutterFullPress()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_FOCUS -> {
                cameraVm.onShutterHalfRelease()
                return true
            }
            KeyEvent.KEYCODE_CAMERA -> return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * A viewfinder frame waits about 1.5 display refreshes between the GPU and
     * the glass, so a 120 Hz panel shows it ~12 ms sooner than at 60 Hz. This
     * is only a request: the system's own refresh-rate cap outranks it, and on
     * the Xperia 1 VII at its default 60 Hz setting the display stays at 60.
     */
    private fun requestFastestDisplayMode() {
        val d = display ?: return
        val current = d.mode
        val fastest = d.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = fastest.modeId }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        orientationListener?.disable()
        orientationListener = null
        super.onDestroy()
    }

    private enum class Screen { CAMERA, DIAGNOSTICS }

    companion object {
        const val EXTRA_RUN_DIAGNOSTICS = "run_diagnostics"
    }
}

@Composable
private fun PermissionPane(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text2("CAMERA PERMISSION REQUIRED", ProTheme.TextPrimary, 14.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text2(
            "GRANT",
            ProTheme.Accent,
            13.sp,
            Modifier.clickable { onRequest() }.padding(12.dp),
        )
    }
}

@Composable
private fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            Diagnostics(context).runAll { line -> lines.add(line) }
        }
        running = false
    }

    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize().background(ProTheme.Black).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text2(
                "< BACK",
                ProTheme.Accent,
                12.sp,
                Modifier.clickable(enabled = !running) { onBack() }.padding(6.dp),
            )
            Spacer(Modifier.height(1.dp))
            Text2(
                if (running) "  RUNNING…" else "  DONE",
                if (running) ProTheme.Accent else ProTheme.Ok,
                12.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.verticalScroll(scroll)) {
            lines.forEach { line ->
                Text2(line, ProTheme.TextPrimary, 9.sp, Modifier.fillMaxWidth())
            }
        }
    }
}
