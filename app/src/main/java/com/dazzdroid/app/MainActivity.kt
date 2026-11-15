package com.dazzdroid.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dazzdroid.app.ui.Amber
import com.dazzdroid.app.ui.CameraScreen
import com.dazzdroid.app.ui.CameraViewModel
import com.dazzdroid.app.ui.DazzTheme
import com.dazzdroid.app.ui.EditorScreen
import com.dazzdroid.app.ui.Ink

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()
    private lateinit var orientationListener: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) vm.deviceOrientation = o
            }
        }
        setContent { DazzTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event?.repeatCount == 0) vm.shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun setMaxBrightness(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}

private fun requiredPermissions(): Array<String> {
    val p = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) p += Manifest.permission.WRITE_EXTERNAL_STORAGE
    return p.toTypedArray()
}

@Composable
private fun App(vm: CameraViewModel) {
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        cameraGranted = res[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) vm.onResume()
    }
    LaunchedEffect(Unit) { permLauncher.launch(requiredPermissions()) }

    var editTarget by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val type = activity.contentResolver.getType(uri) ?: ""
            editTarget = uri to type.startsWith("video")
        }
    }

    // Camera runs only while the camera screen is visible and the activity is resumed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val inEditor = editTarget != null
    DisposableEffect(lifecycle, inEditor, cameraGranted) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && !inEditor && cameraGranted) vm.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) vm.onPause()
        }
        lifecycle.addObserver(obs)
        if (inEditor) vm.onPause()
        else if (cameraGranted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.onResume()
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(vm.screenFlash) { activity.setMaxBrightness(vm.screenFlash) }

    Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
        val target = editTarget
        when {
            !cameraGranted -> PermissionScreen { permLauncher.launch(requiredPermissions()) }
            target != null -> EditorScreen(vm, target.first, target.second) { msg ->
                editTarget = null
                if (msg != null) vm.message = msg
            }
            else -> CameraScreen(
                vm,
                onImport = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onOpenLast = {
                    vm.lastUri?.let { u ->
                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, activity.contentResolver.getType(u) ?: "image/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { activity.startActivity(i) }
                    }
                },
            )
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DAZZDROID", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 28.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Camera access is needed to load film into your cameras. The microphone is used for video sound.",
            color = Color(0xFFE8E0D4), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)) {
            Text("GRANT ACCESS", fontFamily = FontFamily.Monospace)
        }
    }
}
